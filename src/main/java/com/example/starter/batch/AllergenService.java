package com.example.starter.batch;

import com.example.starter.batch.dto.CompatibilityDiagnostic;
import com.example.starter.batch.dto.ComponentVersionResponse;
import com.example.starter.batch.dto.LineageSnapshotResponse;
import com.example.starter.batch.dto.MergeRequest;
import com.example.starter.batch.dto.MergeResponse;
import com.example.starter.batch.dto.ReviseCompositionRequest;
import com.example.starter.batch.dto.RiskResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 批次过敏原隔离与合批联合校验核心服务。
 *
 * <p>成分版本不可变：每次修订追加 allergen_component 新版本，从不更新删除；
 * 合批在目标容器上产生过敏原并集、隔离级别取最高的新版本。
 *
 * <p>合批为单事务：先锁定并校验目标与全部来源（状态、整批库存、最终血缘集合的
 * 隔离级别兼容矩阵、未检验版本门禁），任一不兼容抛 422，整事务回滚，不留血缘或库存半成品。
 *
 * <p>幂等：allergenKey 指纹含操作、批次版本、规范化代码、目标容器与来源；
 * 仅成功命令写 command_log，失败不占键，同键改参 409。
 */
@Service
public class AllergenService {

    private static final String CMD_REVISE = "REVISE_COMPOSITION";
    private static final String CMD_MERGE = "MERGE";

    private static final String SEP = "";
    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 5;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public AllergenService(BatchRepository repo,
                           PlatformTransactionManager transactionManager,
                           ObjectMapper objectMapper) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    // ==================== 成分修订 ====================

    /**
     * 成分修订：expectedVersion 乐观并发（不匹配 409）；未知过敏原代码或空级别 422；
     * 召回来源的后代批次不得降低隔离级别（422）；
     * 已放行（RELEASED）批次修订若引入当前版本没有的新过敏原，转 ALLERGEN_RISK
     * 并保留原放行快照，仅重新检验 + 双角色放行可解除。
     */
    public StoredResponse reviseComposition(String batchKey, ReviseCompositionRequest req) {
        Set<String> catalog = new HashSet<>(repo.findAllergenCatalogCodes());
        List<String> codes = Allergens.validate(req.composition(), catalog);
        SegregationLevel level = req.composition().segregationLevel();
        String fingerprint = fingerprint("revise", batchKey,
                String.valueOf(req.expectedVersion()), String.join(SEP, codes), level.name());
        return executeIdempotent(CMD_REVISE, req.allergenKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = loggedResponse(CMD_REVISE, req.allergenKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }

            BatchRepository.ComponentRow current = repo.findCurrentComponent(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次成分版本不存在: " + batchKey));
            if (current.version() != req.expectedVersion()) {
                throw ApiException.conflict("成分版本冲突：expectedVersion=" + req.expectedVersion()
                        + "，当前版本=" + current.version());
            }
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status == BatchStatus.RECALLED || status == BatchStatus.REJECTED
                    || status == BatchStatus.SPLIT || status == BatchStatus.MERGED) {
                throw ApiException.conflict("批次状态 " + status + " 不允许成分修订");
            }

            List<String> currentCodes = Allergens.fromStored(current.allergenCodes());
            SegregationLevel currentLevel = SegregationLevel.valueOf(current.segregationLevel());
            if (currentCodes.equals(codes) && currentLevel == level) {
                throw ApiException.unprocessable("成分未发生变更，不产生新版本");
            }
            // 召回任一来源（拆分父批或合批目标链）后，后代成分版本不得降低隔离级别
            if (level.ordinal() < currentLevel.ordinal() && hasRecalledAncestor(batchKey)) {
                throw ApiException.unprocessable("祖先来源已召回，后代成分版本不得降低隔离级别: "
                        + currentLevel + " -> " + level);
            }

            String now = now();
            int newVersion = current.version() + 1;
            repo.insertComponent(new BatchRepository.ComponentRow(0L, batchKey, newVersion,
                    Allergens.toStored(codes), level.name(), req.allergenKey(), now));

            BatchStatus newStatus = status;
            boolean riskRaised = false;
            if (status == BatchStatus.RELEASED && introducesNewAllergen(currentCodes, codes)) {
                // 已放行批次发现新增过敏原：转 ALLERGEN_RISK，保留原放行快照
                newStatus = BatchStatus.ALLERGEN_RISK;
                repo.updateStatus(batchKey, newStatus.name());
                raiseRisk(batchKey, newVersion, currentCodes, codes, now);
                riskRaised = true;
            } else if (status == BatchStatus.PENDING_RELEASE
                    || status == BatchStatus.RELEASE_REVIEW) {
                // 新版本尚未检验：回到隔离，原检验/批准记录保留但不再满足当前版本门禁
                newStatus = BatchStatus.QUARANTINED;
                repo.updateStatus(batchKey, newStatus.name());
            }

            ReviseResult body = new ReviseResult(batchKey, newVersion, codes, level,
                    newStatus, riskRaised, java.time.Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 新代码集合是否引入了旧集合中不存在的过敏原。
     */
    private boolean introducesNewAllergen(List<String> oldCodes, List<String> newCodes) {
        Set<String> old = new HashSet<>(oldCodes);
        for (String code : newCodes) {
            if (!old.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写风险记录，release_snapshot 保存风险发生时的放行版本、时间与双角色批准人。
     */
    private void raiseRisk(String batchKey, int detectedVersion, List<String> currentCodes,
                           List<String> newCodes, String now) {
        List<BatchRepository.ApprovalRow> approvals = repo.findApprovals(batchKey);
        int maxRound = approvals.stream().mapToInt(BatchRepository.ApprovalRow::round).max().orElse(1);
        List<BatchRepository.ApprovalRow> lastRound = approvals.stream()
                .filter(a -> a.round() == maxRound).toList();
        String quality = null;
        String operations = null;
        String releasedAt = now;
        for (BatchRepository.ApprovalRow a : lastRound) {
            if ("QUALITY".equals(a.role())) {
                quality = a.actorId();
            } else if ("OPERATIONS".equals(a.role())) {
                operations = a.actorId();
                releasedAt = a.createdAt();
            }
        }
        // 风险版本基于的已放行成分版本为 detectedVersion - 1
        RiskReleaseSnapshot snapshot = new RiskReleaseSnapshot(detectedVersion - 1,
                java.time.Instant.parse(releasedAt), quality, operations);
        List<String> added = newCodes.stream()
                .filter(c -> !new HashSet<>(currentCodes).contains(c)).sorted().toList();
        repo.insertRisk(new BatchRepository.RiskRow(0L, batchKey, detectedVersion,
                Allergens.toStored(added), toJson(snapshot), now, null, null));
    }

    // ==================== 批量合批 ====================

    /**
     * 批量合批：整批并入（来源数量必须等于其全部库存），目标产生过敏原并集、最高隔离级别的新版本，
     * 来源置 MERGED、库存归零；不同隔离级别须在目标容器声明的兼容矩阵中。
     * 先校验全部目标状态，任一不兼容 422 并回滚全部血缘和库存。
     */
    public StoredResponse merge(MergeRequest req) {
        String targetKey = req.targetBatchKey();
        // 请求级别引用在事务外解析，非法级别 422（可区分原因）
        List<SegregationLevel> levels = parsePairs(req.compatibleLevels());
        String fingerprint = buildMergeFingerprint(targetKey, req.sources(), levels);
        return executeIdempotent(CMD_MERGE, req.allergenKey(), fingerprint, () -> {
            // 以确定顺序锁定全部参与批次（目标 + 来源），避免并发合批死锁
            List<String> sourceKeys = req.sources().stream().map(MergeRequest.MergeSource::batchKey).toList();
            List<String> lockOrder = new ArrayList<>(new TreeSet<>(sourceKeys));
            if (!lockOrder.contains(targetKey)) {
                lockOrder.add(targetKey);
                Collections.sort(lockOrder);
            }
            for (String key : lockOrder) {
                repo.findBatchForUpdate(key);
            }
            // 行锁后重查命令快照
            var logged = loggedResponse(CMD_MERGE, req.allergenKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }

            MergeEvaluation evaluation = evaluateMerge(targetKey, req.sources(), levels);

            // 全部校验通过：先声明兼容矩阵（只增，重复对忽略）
            String now = now();
            persistCompatPairs(targetKey, levels, now);

            BigDecimal targetBefore = evaluation.targetStock;
            BigDecimal credited = BigDecimal.ZERO;
            List<MergeResponse.MergedSource> sourceBodies = new ArrayList<>();
            // 扣减每个来源、写合批血缘边
            for (int i = 0; i < evaluation.sources.size(); i++) {
                ValidatedSource src = evaluation.sources.get(i);
                BigDecimal after = src.stock.subtract(src.quantity);
                repo.updateStock(src.key, after, now);
                repo.insertStockLedger(src.key, "DEBIT_MERGE", req.allergenKey(),
                        src.quantity.negate(), after, now);
                repo.updateStatus(src.key, BatchStatus.MERGED.name());
                repo.insertMergeLineage(new BatchRepository.MergeLineageRow(0L, req.allergenKey(),
                        targetKey, src.key, i + 1, src.quantity, now));
                credited = credited.add(src.quantity);
                sourceBodies.add(new MergeResponse.MergedSource(src.key, src.version,
                        src.quantity, after));
            }
            // 目标库存增加（守恒：增加额恰为全部来源扣减额之和）
            BigDecimal targetAfter = targetBefore.add(credited);
            repo.updateStock(targetKey, targetAfter, now);
            repo.insertStockLedger(targetKey, "CREDIT_MERGE", req.allergenKey(),
                    credited, targetAfter, now);
            // 目标新成分版本：过敏原并集，隔离级别取最高
            int newTargetVersion = evaluation.targetVersion + 1;
            repo.insertComponent(new BatchRepository.ComponentRow(0L, targetKey, newTargetVersion,
                    Allergens.toStored(evaluation.mergedCodes), evaluation.mergedLevel.name(),
                    req.allergenKey(), now));
            // 新版本尚未检验：目标回到隔离，重新检验 + 双角色放行后方可再放行
            repo.updateStatus(targetKey, BatchStatus.QUARANTINED.name());

            MergeResponse body = new MergeResponse(req.allergenKey(), targetKey, newTargetVersion,
                    targetBefore, targetAfter, sourceBodies, java.time.Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 合批前的纯校验聚合：任何一条不满足都收集后抛出携带全部原因的 422。
     */
    private MergeEvaluation evaluateMerge(String targetKey, List<MergeRequest.MergeSource> sourceReqs,
                                          List<SegregationLevel> declaredPairs) {
        BatchRepository.BatchRow target = repo.findBatch(targetKey)
                .orElseThrow(() -> ApiException.notFound("目标容器不存在: " + targetKey));
        BatchStatus targetStatus = BatchStatus.valueOf(target.status());
        // 召回祖先是硬性安全阻断，优先于状态校验返回可区分原因
        if (hasRecalledAncestor(targetKey)) {
            throw ApiException.unprocessable("目标容器存在已召回祖先来源，禁止合批: " + targetKey);
        }
        // 目标容器必须为已放行稳态：合批产生未经检验的新版本，容器随之回到隔离重新检验放行
        if (targetStatus != BatchStatus.RELEASED) {
            throw ApiException.unprocessable("目标容器状态 " + targetStatus
                    + " 不允许合批，仅 RELEASED 批次可作为合批目标容器");
        }
        BatchRepository.ComponentRow targetComponent = repo.findCurrentComponent(targetKey)
                .orElseThrow(() -> ApiException.notFound("目标容器成分版本不存在: " + targetKey));
        BigDecimal targetStock = repo.findStock(targetKey)
                .map(BatchRepository.StockRow::quantity)
                .orElseThrow(() -> ApiException.unprocessable("目标容器库存不存在: " + targetKey));

        // 目标容器已声明 + 本次声明 的兼容矩阵（无序级别对，按严格度规范化）
        Set<LevelPair> matrix = loadMatrix(targetKey);
        for (int i = 0; i + 1 < declaredPairs.size(); i += 2) {
            matrix.add(new LevelPair(declaredPairs.get(i), declaredPairs.get(i + 1)));
        }

        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ValidatedSource> valid = new ArrayList<>();
        TreeSet<String> mergedCodeSet = new TreeSet<>(
                Allergens.fromStored(targetComponent.allergenCodes()));
        // 兼容矩阵以目标容器当前级别为准：来源级别与目标级别不同时必须命中矩阵声明；
        // 合并后目标新版本级别取目标与全部来源的最高级别。
        SegregationLevel targetLevel = SegregationLevel.valueOf(targetComponent.segregationLevel());
        SegregationLevel mergedLevel = targetLevel;

        for (MergeRequest.MergeSource sourceReq : sourceReqs) {
            String key = sourceReq.batchKey();
            if (key.equals(targetKey)) {
                throw ApiException.unprocessable("目标容器不能同时作为合批来源: " + key);
            }
            if (!seen.add(key)) {
                throw ApiException.unprocessable("合批来源在请求内重复: " + key);
            }
            BatchRepository.BatchRow source = repo.findBatch(key)
                    .orElseThrow(() -> ApiException.notFound("合批来源不存在: " + key));
            BatchStatus sourceStatus = BatchStatus.valueOf(source.status());
            // 已被合批消费优先判定，给出最具体的可区分原因
            if (repo.findMergeLineageBySource(key).isPresent()) {
                problems.add("来源 " + key + " 已被合批，不可重复合入");
                continue;
            }
            if (sourceStatus != BatchStatus.RELEASED) {
                problems.add("来源 " + key + " 状态为 " + sourceStatus + "，仅 RELEASED 批次可合入");
                continue;
            }
            if (hasRecalledAncestor(key)) {
                problems.add("来源 " + key + " 存在已召回祖先，禁止合入");
                continue;
            }
            BatchRepository.ComponentRow component = repo.findCurrentComponent(key).orElse(null);
            if (component == null) {
                problems.add("来源 " + key + " 缺少成分版本");
                continue;
            }
            // 放行门禁：来源当前成分版本必做项必须全部 PASS（RELEASED 通常满足，显式校验）
            List<String> required = repo.findRequiredTests(key);
            if (!allRequiredPassedAtVersion(key, required, component.version())) {
                problems.add("来源 " + key + " 当前成分版本 " + component.version()
                        + " 存在未检验通过的必做项，阻断合批");
                continue;
            }
            BigDecimal stock = repo.findStock(key)
                    .map(BatchRepository.StockRow::quantity).orElse(BigDecimal.ZERO);
            BigDecimal quantity = sourceReq.quantity();
            if (stock.signum() <= 0) {
                problems.add("来源 " + key + " 库存为零，不能整批合入");
                continue;
            }
            if (stock.compareTo(quantity) != 0) {
                problems.add("来源 " + key + " 合批数量 " + quantity.stripTrailingZeros().toPlainString()
                        + " 必须等于其全部库存 " + stock.stripTrailingZeros().toPlainString() + "（整批合入）");
                continue;
            }
            SegregationLevel sourceLevel = SegregationLevel.valueOf(component.segregationLevel());
            // 来源隔离级别与目标容器当前级别不同时，必须命中目标容器声明的兼容矩阵
            if (sourceLevel != targetLevel
                    && !matrix.contains(new LevelPair(minLevel(targetLevel, sourceLevel),
                            maxLevel(targetLevel, sourceLevel)))) {
                problems.add("来源 " + key + " 隔离级别 " + sourceLevel
                        + " 与目标容器级别 " + targetLevel + " 未在兼容矩阵声明");
            }
            mergedCodeSet.addAll(Allergens.fromStored(component.allergenCodes()));
            if (sourceLevel.ordinal() > mergedLevel.ordinal()) {
                mergedLevel = sourceLevel;
            }
            valid.add(new ValidatedSource(key, component.version(), quantity, stock, sourceLevel));
        }
        if (!problems.isEmpty()) {
            throw ApiException.unprocessable("合批校验失败: " + String.join("; ", problems));
        }
        return new MergeEvaluation(targetStock,
                targetComponent.version(),
                List.copyOf(mergedCodeSet), mergedLevel, List.copyOf(valid));
    }

    /**
     * 解析本次请求声明的兼容级别对；级别字符串非法或两端相同均为 422/400 可区分错误。
     * 返回扁平的 [low, high, low, high, ...]，已按严格度规范化。
     */
    private List<SegregationLevel> parsePairs(List<MergeRequest.LevelPair> pairs) {
        List<SegregationLevel> flat = new ArrayList<>();
        if (pairs == null) {
            return flat;
        }
        for (MergeRequest.LevelPair pair : pairs) {
            if (pair == null || pair.fromLevel() == null || pair.toLevel() == null
                    || pair.fromLevel().level() == null || pair.toLevel().level() == null) {
                throw ApiException.badRequest("兼容级别对不能为空");
            }
            SegregationLevel a = parseLevel(pair.fromLevel().level());
            SegregationLevel b = parseLevel(pair.toLevel().level());
            if (a == b) {
                throw ApiException.badRequest("兼容级别对两端不能相同: " + a);
            }
            flat.add(minLevel(a, b));
            flat.add(maxLevel(a, b));
        }
        return flat;
    }

    private SegregationLevel parseLevel(String raw) {
        try {
            return SegregationLevel.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("未知隔离级别: " + raw);
        }
    }

    private void persistCompatPairs(String targetKey, List<SegregationLevel> flat, String now) {
        Set<LevelPair> existing = loadMatrix(targetKey);
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            LevelPair pair = new LevelPair(flat.get(i), flat.get(i + 1));
            if (existing.add(pair)) {
                repo.insertMergeCompat(new BatchRepository.CompatRow(0L, targetKey,
                        pair.low().name(), pair.high().name(), now));
            }
        }
    }

    private Set<LevelPair> loadMatrix(String targetKey) {
        Set<LevelPair> matrix = new HashSet<>();
        for (BatchRepository.CompatRow row : repo.findMergeCompat(targetKey)) {
            matrix.add(new LevelPair(SegregationLevel.valueOf(row.levelA()),
                    SegregationLevel.valueOf(row.levelB())));
        }
        return matrix;
    }

    private String buildMergeFingerprint(String targetKey, List<MergeRequest.MergeSource> sources,
                                         List<SegregationLevel> levels) {
        List<String> parts = new ArrayList<>();
        parts.add("merge");
        parts.add(targetKey);
        // 来源按请求顺序（seq 有业务意义）；来源成分版本与代码不可变，合批后重放仍得到相同指纹
        for (MergeRequest.MergeSource s : sources) {
            BatchRepository.ComponentRow component = repo.findCurrentComponent(s.batchKey()).orElse(null);
            parts.add(s.batchKey());
            parts.add("v" + (component == null ? 0 : component.version()));
            parts.add(component == null ? "" : component.allergenCodes());
            parts.add(component == null ? "" : component.segregationLevel());
            parts.add(s.quantity().stripTrailingZeros().toPlainString());
        }
        // 兼容对规范化排序，使声明顺序不同也视为同参
        TreeSet<String> pairKeys = new TreeSet<>();
        for (int i = 0; i + 1 < levels.size(); i += 2) {
            pairKeys.add(levels.get(i).name() + "-" + levels.get(i + 1).name());
        }
        parts.add("matrix=" + String.join(",", pairKeys));
        return fingerprint(parts.toArray(new String[0]));
    }

    // ==================== 诊断与查询 ====================

    /**
     * 兼容诊断：复现合批校验但不落任何状态；compatible=true 可合批，否则给出阻断原因与缺失级别对。
     */
    public CompatibilityDiagnostic diagnose(String targetBatchKey, MergeRequest req) {
        List<SegregationLevel> declared = parsePairs(req.compatibleLevels());
        BatchRepository.BatchRow target = repo.findBatch(targetBatchKey)
                .orElseThrow(() -> ApiException.notFound("目标容器不存在: " + targetBatchKey));
        BatchRepository.ComponentRow targetComponent = repo.findCurrentComponent(targetBatchKey).orElse(null);
        SegregationLevel targetLevel = targetComponent == null
                ? SegregationLevel.NONE : SegregationLevel.valueOf(targetComponent.segregationLevel());

        Set<LevelPair> matrix = loadMatrix(targetBatchKey);
        for (int i = 0; i + 1 < declared.size(); i += 2) {
            matrix.add(new LevelPair(declared.get(i), declared.get(i + 1)));
        }

        List<String> reasons = new ArrayList<>();
        List<CompatibilityDiagnostic.BlockedPair> blocked = new ArrayList<>();
        List<CompatibilityDiagnostic.SourceProfile> profiles = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (BatchStatus.valueOf(target.status()) != BatchStatus.RELEASED) {
            reasons.add("目标容器状态为 " + target.status() + "，仅 RELEASED 可作为合批目标");
        }
        if (hasRecalledAncestor(targetBatchKey)) {
            reasons.add("目标容器存在已召回祖先来源");
        }
        for (MergeRequest.MergeSource sourceReq : req.sources()) {
            String key = sourceReq.batchKey();
            if (!seen.add(key)) {
                reasons.add("来源 " + key + " 在请求内重复");
                continue;
            }
            var sourceOpt = repo.findBatch(key);
            if (sourceOpt.isEmpty()) {
                reasons.add("来源 " + key + " 不存在");
                continue;
            }
            BatchRepository.ComponentRow component = repo.findCurrentComponent(key).orElse(null);
            List<String> codes = component == null
                    ? List.of() : Allergens.fromStored(component.allergenCodes());
            SegregationLevel level = component == null
                    ? SegregationLevel.NONE : SegregationLevel.valueOf(component.segregationLevel());
            boolean testedAtCurrent = component != null
                    && allRequiredPassedAtVersion(key, repo.findRequiredTests(key), component.version());
            BigDecimal quantity = repo.findStock(key)
                    .map(BatchRepository.StockRow::quantity).orElse(BigDecimal.ZERO);
            profiles.add(new CompatibilityDiagnostic.SourceProfile(key,
                    component == null ? 0 : component.version(), codes, level,
                    testedAtCurrent, quantity));

            BatchStatus sourceStatus = BatchStatus.valueOf(sourceOpt.get().status());
            if (sourceStatus != BatchStatus.RELEASED) {
                reasons.add("来源 " + key + " 状态为 " + sourceStatus + "，仅 RELEASED 可合入");
            }
            if (repo.findMergeLineageBySource(key).isPresent()) {
                reasons.add("来源 " + key + " 已被合批");
            }
            if (hasRecalledAncestor(key)) {
                reasons.add("来源 " + key + " 存在已召回祖先");
            }
            if (!testedAtCurrent) {
                reasons.add("来源 " + key + " 当前成分版本未全部检验通过");
            }
            if (level != targetLevel
                    && !matrix.contains(new LevelPair(minLevel(level, targetLevel),
                            maxLevel(level, targetLevel)))) {
                reasons.add("来源 " + key + " 级别 " + level + " 与目标级别 "
                        + targetLevel + " 缺少兼容矩阵声明");
                blocked.add(new CompatibilityDiagnostic.BlockedPair(key,
                        minLevel(level, targetLevel), maxLevel(level, targetLevel)));
            }
        }
        return new CompatibilityDiagnostic(targetBatchKey, reasons.isEmpty(), reasons, blocked,
                targetLevel, profiles);
    }

    /**
     * 查询批次全部不可变成分版本（版本号升序），current 标记当前版本。
     */
    public List<ComponentVersionResponse> listComponents(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<BatchRepository.ComponentRow> rows = repo.findComponents(batchKey);
        int maxVersion = rows.stream().mapToInt(BatchRepository.ComponentRow::version).max().orElse(0);
        return rows.stream()
                .map(r -> new ComponentVersionResponse(r.batchKey(), r.version(),
                        Allergens.fromStored(r.allergenCodes()),
                        SegregationLevel.valueOf(r.segregationLevel()),
                        r.version() == maxVersion, java.time.Instant.parse(r.createdAt())))
                .toList();
    }

    /**
     * 查询当前未解除的过敏原风险；无未解除风险返回 null。
     */
    public RiskResponse currentRisk(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        BatchRepository.RiskRow row = repo.findOpenRisk(batchKey).orElse(null);
        if (row == null) {
            return null;
        }
        RiskReleaseSnapshot snapshot = fromJson(row.releaseSnapshot(), RiskReleaseSnapshot.class);
        return new RiskResponse(batchKey, BatchStatus.ALLERGEN_RISK, row.detectedVersion(),
                Allergens.fromStored(row.newAllergenCodes()),
                new RiskResponse.ReleaseSnapshot(snapshot.componentVersion(), snapshot.releasedAt(),
                        snapshot.qualityApprover(), snapshot.operationsApprover()),
                false, java.time.Instant.parse(row.detectedAt()),
                row.resolvedAt() == null ? null : java.time.Instant.parse(row.resolvedAt()));
    }

    /**
     * 成分血缘快照：批次自身成分画像 + 拆分父子边 + 合批来源/去向边。
     */
    public LineageSnapshotResponse lineageSnapshot(String batchKey) {
        BatchRepository.BatchRow self = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        LineageSnapshotResponse.ComponentNode selfNode = toNode(self);

        List<LineageSnapshotResponse.SplitEdge> splitParents = new ArrayList<>();
        List<LineageSnapshotResponse.SplitEdge> splitChildren = new ArrayList<>();
        for (BatchRepository.LineageRow edge : repo.findAllLineage()) {
            int parentVersion = repo.findCurrentComponent(edge.parentKey())
                    .map(BatchRepository.ComponentRow::version).orElse(0);
            LineageSnapshotResponse.SplitEdge dto = new LineageSnapshotResponse.SplitEdge(
                    edge.parentKey(), edge.childKey(), parentVersion,
                    java.time.Instant.parse(edge.createdAt()));
            if (edge.childKey().equals(batchKey)) {
                splitParents.add(dto);
            }
            if (edge.parentKey().equals(batchKey)) {
                splitChildren.add(dto);
            }
        }
        List<LineageSnapshotResponse.MergeEdge> mergedSources = new ArrayList<>();
        LineageSnapshotResponse.MergeEdge mergedInto = null;
        for (BatchRepository.MergeLineageRow edge : repo.findAllMergeLineage()) {
            int sourceVersion = repo.findCurrentComponent(edge.sourceKey())
                    .map(BatchRepository.ComponentRow::version).orElse(0);
            LineageSnapshotResponse.MergeEdge dto = new LineageSnapshotResponse.MergeEdge(
                    edge.commandKey(), edge.targetKey(), edge.sourceKey(), sourceVersion,
                    edge.quantity(), java.time.Instant.parse(edge.createdAt()));
            if (edge.targetKey().equals(batchKey)) {
                mergedSources.add(dto);
            }
            if (edge.sourceKey().equals(batchKey)) {
                mergedInto = dto;
            }
        }
        return new LineageSnapshotResponse(batchKey, selfNode, splitParents, splitChildren,
                mergedSources, mergedInto);
    }

    private LineageSnapshotResponse.ComponentNode toNode(BatchRepository.BatchRow row) {
        BatchRepository.ComponentRow c = repo.findCurrentComponent(row.batchKey()).orElse(null);
        return new LineageSnapshotResponse.ComponentNode(row.batchKey(), row.batchNo(),
                BatchStatus.valueOf(row.status()), c == null ? 0 : c.version(),
                c == null ? List.of() : Allergens.fromStored(c.allergenCodes()),
                c == null ? SegregationLevel.NONE : SegregationLevel.valueOf(c.segregationLevel()));
    }

    // ==================== 共享辅助 ====================

    /**
     * 当前成分版本的必做项是否全部 PASS（放行/合批门禁）。
     */
    private boolean allRequiredPassedAtVersion(String batchKey, List<String> required, int version) {
        Set<String> passed = new HashSet<>();
        for (BatchRepository.TestRow t : repo.findTests(batchKey)) {
            if (t.componentVersion() == version && TestOutcome.PASS.name().equals(t.outcome())) {
                passed.add(t.testItem());
            }
        }
        return passed.containsAll(required);
    }

    /**
     * 沿物料来源方向（祖先）向上，是否存在任一被直接召回的祖先：
     * 拆分边 child→parent；合批边 target→source（目标容器的物料来自各来源，来源是目标的祖先）。
     */
    boolean hasRecalledAncestor(String batchKey) {
        Map<String, List<String>> ancestors = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            ancestors.computeIfAbsent(row.childKey(), k -> new ArrayList<>()).add(row.parentKey());
        }
        for (BatchRepository.MergeLineageRow row : repo.findAllMergeLineage()) {
            ancestors.computeIfAbsent(row.targetKey(), k -> new ArrayList<>()).add(row.sourceKey());
        }
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        Set<String> visited = new HashSet<>();
        List<String> frontier = new ArrayList<>();
        addParents(batchKey, ancestors, frontier);
        while (!frontier.isEmpty()) {
            String current = frontier.remove(frontier.size() - 1);
            if (!visited.add(current)) {
                continue;
            }
            if (recalled.contains(current)) {
                return true;
            }
            addParents(current, ancestors, frontier);
        }
        return false;
    }

    private void addParents(String key, Map<String, List<String>> ancestors,
                            List<String> frontier) {
        List<String> ups = ancestors.get(key);
        if (ups != null) {
            frontier.addAll(ups);
        }
    }

    private StoredResponse executeIdempotent(String type, String commandKey, String fingerprint,
                                             Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var logged = loggedResponse(type, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    repo.insertCommand(new BatchRepository.CommandRow(type, commandKey, fingerprint,
                            response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同键插入冲突：回滚重试，读取对方已提交快照
            } catch (ConcurrencyFailureException e) {
                // 行锁冲突/死锁牺牲品：回滚后重试；合批/修订在锁内重新校验状态，重试安全
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    private Optional<StoredResponse> loggedResponse(String type, String commandKey,
                                                    String fingerprint) {
        var existing = repo.findCommand(type, commandKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("allergenKey 已以不同参数使用: " + commandKey);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    private static SegregationLevel minLevel(SegregationLevel a, SegregationLevel b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    private static SegregationLevel maxLevel(SegregationLevel a, SegregationLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private String now() {
        return java.time.Instant.now().toString();
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("快照反序列化失败", e);
        }
    }

    private String fingerprint(String... parts) {
        String canonical = String.join(SEP, parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 无顺序的隔离级别对，按严格度规范化（low 在前）。
     */
    private record LevelPair(SegregationLevel low, SegregationLevel high) {
    }

    /**
     * 已通过全部前置校验的合批来源。
     */
    private record ValidatedSource(String key, int version, BigDecimal quantity,
                                   BigDecimal stock, SegregationLevel level) {
    }

    /**
     * 合批校验聚合结果。
     */
    private record MergeEvaluation(BigDecimal targetStock, int targetVersion,
                                   List<String> mergedCodes, SegregationLevel mergedLevel,
                                   List<ValidatedSource> sources) {
    }

    /**
     * 修订响应：新版本号、修订后状态与是否触发风险。
     */
    public record ReviseResult(String batchKey, int version, List<String> allergenCodes,
                               SegregationLevel segregationLevel, BatchStatus batchStatus,
                               boolean riskRaised, java.time.Instant createdAt) {
    }

    /**
     * 风险记录内持久化的原放行快照。
     */
    private record RiskReleaseSnapshot(int componentVersion, java.time.Instant releasedAt,
                                       String qualityApprover, String operationsApprover) {
    }
}
