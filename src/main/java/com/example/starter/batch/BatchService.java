package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.MergeRequest;
import com.example.starter.batch.dto.MergeResponse;
import com.example.starter.batch.dto.PotencyEvidenceResponse;
import com.example.starter.batch.dto.PotencyModelRequest;
import com.example.starter.batch.dto.PotencyModelResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SplitResponse;
import com.example.starter.batch.dto.SubmitTestRequest;
import com.example.starter.batch.dto.TestResultResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 批次隔离与放行核心服务。
 *
 * <p>并发策略：同一批次的检验/批准/召回在事务内对 batch 行 SELECT ... FOR UPDATE 串行化，
 * 按事务提交顺序生效；commandKey 幂等通过 command_log 主键 + 参数指纹实现，
 * 同键同参重放返回首次响应快照，同键改参返回 409；仅成功命令写入 command_log（失败不占键）。
 *
 * <p>效价衰减：批次可登记初始效价、最低放行效价与按 UTC 日（左闭右开）的线性衰减率；
 * 放行（第二笔批准）、拆分、合批在 potency_model_lock 单行锁内读取一致的模型版本并固化证据，
 * 与模型修订按提交顺序裁决；证据只增不改，后续模型修订不回写历史结论。
 */
@Service
public class BatchService {

    private static final String CMD_CREATE = "CREATE_BATCH";
    private static final String CMD_TEST = "SUBMIT_TEST";
    private static final String CMD_APPROVE = "APPROVE";
    private static final String CMD_RECALL = "RECALL";
    private static final String CMD_SPLIT = "SPLIT";
    private static final String CMD_MERGE = "MERGE";
    private static final String CMD_MODEL = "POTENCY_MODEL";

    private static final String OP_RELEASE = "RELEASE";
    private static final String OP_SPLIT = "SPLIT";
    private static final String OP_MERGE = "MERGE";

    /**
     * 支持的效价舍入模式（java.math.RoundingMode 同名）。
     */
    private static final Set<String> ALLOWED_ROUNDING_MODES =
            Set.of("HALF_UP", "HALF_EVEN", "DOWN", "UP");

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BatchService(BatchRepository repo,
                        PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper,
                        Clock clock) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建批次：初始状态 QUARANTINED；batchKey 全局唯一，重复返回 409。
     * 效价四元组（初始效价/最低放行效价/衰减率/数量）必须同时登记或同时缺省；
     * 效价、阈值或衰减率为负、数量非正返回 422；登记后衰减起算时刻为 producedAt。
     */
    public StoredResponse createBatch(CreateBatchRequest req) {
        List<String> items = req.requiredTests().stream().map(String::trim).toList();
        if (new HashSet<>(items).size() != items.size()) {
            throw ApiException.badRequest("requiredTests 存在重复检验项");
        }
        boolean anyPotency = req.initialPotency() != null || req.minReleasePotency() != null
                || req.decayRatePerDay() != null || req.quantity() != null;
        boolean allPotency = req.initialPotency() != null && req.minReleasePotency() != null
                && req.decayRatePerDay() != null && req.quantity() != null;
        if (anyPotency && !allPotency) {
            throw ApiException.badRequest(
                    "初始效价、最低放行效价、衰减率与数量必须同时登记或同时缺省");
        }
        if (allPotency) {
            if (req.initialPotency().signum() < 0) {
                throw ApiException.unprocessable("初始效价不得为负",
                        req.initialPotency(), BigDecimal.ZERO);
            }
            if (req.minReleasePotency().signum() < 0) {
                throw ApiException.unprocessable("最低放行效价不得为负",
                        req.minReleasePotency(), BigDecimal.ZERO);
            }
            if (req.decayRatePerDay().signum() < 0) {
                throw ApiException.unprocessable("衰减率不得为负",
                        req.decayRatePerDay(), BigDecimal.ZERO);
            }
            if (req.quantity().signum() <= 0) {
                throw ApiException.unprocessable("数量必须为正",
                        req.quantity(), BigDecimal.ZERO);
            }
        }
        Map<String, Object> fp = new TreeMap<>();
        fp.put("op", "create");
        fp.put("batchKey", req.batchKey());
        fp.put("productCode", req.productCode());
        fp.put("batchNo", req.batchNo());
        fp.put("producedAt", req.producedAt().toString());
        fp.put("requiredTests", items);
        fp.put("initialPotency", decimalText(req.initialPotency()));
        fp.put("minReleasePotency", decimalText(req.minReleasePotency()));
        fp.put("decayRatePerDay", decimalText(req.decayRatePerDay()));
        fp.put("quantity", decimalText(req.quantity()));
        return executeIdempotent(CMD_CREATE, req.commandKey(), fingerprint(fp), () -> {
            repo.findBatch(req.batchKey()).ifPresent(b -> {
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            });
            String now = now();
            String epoch = allPotency ? req.producedAt().toString() : null;
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(), req.productCode(),
                    req.batchNo(), req.producedAt().toString(), BatchStatus.QUARANTINED.name(), now,
                    text(req.quantity()), text(req.initialPotency()), text(req.minReleasePotency()),
                    text(req.decayRatePerDay()), epoch));
            for (int i = 0; i < items.size(); i++) {
                repo.insertRequiredTest(req.batchKey(), items.get(i), i + 1);
            }
            BatchResponse body = new BatchResponse(req.batchKey(), req.productCode(), req.batchNo(),
                    req.producedAt(), BatchStatus.QUARANTINED, items, Instant.parse(now),
                    req.initialPotency(), req.minReleasePotency(), req.decayRatePerDay(),
                    req.quantity(), allPotency ? req.producedAt() : null);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 提交检验结果：testKey 批次内幂等；任一 FAIL 立即 REJECTED；全部必做项 PASS 后 PENDING_RELEASE。
     */
    public StoredResponse submitTest(String batchKey, SubmitTestRequest req) {
        Map<String, Object> fp = new TreeMap<>();
        fp.put("op", "test");
        fp.put("batchKey", batchKey);
        fp.put("testKey", req.testKey());
        fp.put("testItem", req.testItem());
        fp.put("result", req.result().name());
        fp.put("inspector", req.inspector());
        return executeIdempotent(CMD_TEST, req.commandKey(), fingerprint(fp), () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));

            var existing = repo.findTest(batchKey, req.testKey());
            if (existing.isPresent()) {
                BatchRepository.TestRow row = existing.get();
                boolean sameContent = row.testItem().equals(req.testItem())
                        && row.outcome().equals(req.result().name())
                        && row.inspector().equals(req.inspector());
                if (!sameContent) {
                    throw ApiException.conflict("testKey 已以不同内容提交: " + req.testKey());
                }
                List<String> required = repo.findRequiredTests(batchKey);
                BatchStatus snapshot = snapshotAfter(row, repo.findTests(batchKey), required);
                return new StoredResponse(200, toJson(toTestResponse(row, snapshot.name())));
            }

            BatchStatus status = BatchStatus.valueOf(batch.status());
            assertNoRecalledAncestor(batchKey);
            if (status == BatchStatus.REJECTED || status == BatchStatus.RELEASED
                    || status == BatchStatus.RECALLED || status == BatchStatus.SPLIT
                    || status == BatchStatus.MERGED) {
                throw ApiException.conflict("批次状态 " + status + " 不允许提交检验");
            }
            List<String> required = repo.findRequiredTests(batchKey);
            if (!required.contains(req.testItem())) {
                throw ApiException.unprocessable("检验项不属于该批次必做项: " + req.testItem());
            }
            boolean itemAlreadyTested = repo.findTests(batchKey).stream()
                    .anyMatch(t -> t.testItem().equals(req.testItem()));
            if (itemAlreadyTested) {
                throw ApiException.conflict("该检验项已存在检验结果: " + req.testItem());
            }

            String now = now();
            repo.insertTest(new BatchRepository.TestRow(0L, batchKey, req.testKey(), req.testItem(),
                    req.result().name(), req.inspector(), now));

            BatchStatus newStatus = status;
            if (req.result() == TestOutcome.FAIL) {
                newStatus = BatchStatus.REJECTED;
            } else if (status == BatchStatus.QUARANTINED && allRequiredPassed(batchKey, required)) {
                newStatus = BatchStatus.PENDING_RELEASE;
            }
            if (newStatus != status) {
                repo.updateStatus(batchKey, newStatus.name());
            }
            TestResultResponse body = new TestResultResponse(batchKey, req.testKey(), req.testItem(),
                    req.result(), req.inspector(), newStatus, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批准：两步放行。首个有效批准进入 RELEASE_REVIEW，第二个不同角色、不同批准人批准后 RELEASED。
     * 落定 RELEASED 的批准在模型锁内按可注入时钟计算当前效价并按当前模型版本舍入，
     * 低于最低放行效价返回 422（携带实际值/要求值/差额），整笔批准不生效；
     * 通过时固化 RELEASE 证据（计算时刻、模型版本、输入效价、舍入结果）。
     */
    public StoredResponse approve(String batchKey, String actorId, String roleHeader, ApproveRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        ApprovalRole role = parseRole(roleHeader);
        String actor = actorId.trim();
        Map<String, Object> fp = new TreeMap<>();
        fp.put("op", "approve");
        fp.put("batchKey", batchKey);
        fp.put("actor", actor);
        fp.put("role", role.name());
        return executeIdempotent(CMD_APPROVE, req.commandKey(), fingerprint(fp), () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            BatchStatus status = BatchStatus.valueOf(batch.status());
            assertNoRecalledAncestor(batchKey);
            if (status == BatchStatus.QUARANTINED) {
                throw ApiException.unprocessable("必做检验项未全部通过，不能批准");
            }
            if (status != BatchStatus.PENDING_RELEASE && status != BatchStatus.RELEASE_REVIEW) {
                throw ApiException.conflict("批次状态 " + status + " 不允许批准");
            }
            boolean actorInspected = repo.findTests(batchKey).stream()
                    .anyMatch(t -> t.inspector().equals(actor));
            if (actorInspected) {
                throw ApiException.unprocessable("批准人不得为该批次任一检验结果的检验人: " + actor);
            }

            List<BatchRepository.ApprovalRow> approvals = repo.findApprovals(batchKey);
            String now = now();
            int seq;
            BatchStatus newStatus;
            if (approvals.isEmpty()) {
                seq = 1;
                newStatus = BatchStatus.RELEASE_REVIEW;
            } else {
                BatchRepository.ApprovalRow first = approvals.get(0);
                if (first.role().equals(role.name())) {
                    throw ApiException.conflict("角色 " + role + " 已批准过，需要另一种角色");
                }
                if (first.actorId().equals(actor)) {
                    throw ApiException.conflict("两个批准人必须不同: " + actor);
                }
                seq = 2;
                newStatus = BatchStatus.RELEASED;
            }
            if (newStatus == BatchStatus.RELEASED && batch.initialPotency() != null) {
                // 放行效价闸：模型锁内读取一致模型版本，按操作时刻计算并固化证据
                repo.lockPotencyModel();
                BatchRepository.ModelRow model = latestModel();
                Instant computedAt = Instant.parse(now);
                BigDecimal raw = PotencyCalculator.currentPotency(dec(batch.initialPotency()),
                        dec(batch.decayRatePerDay()), Instant.parse(batch.potencyEpochAt()),
                        computedAt);
                BigDecimal rounded = PotencyCalculator.round(raw, model.scale(), model.roundingMode());
                BigDecimal threshold = dec(batch.minReleasePotency());
                if (rounded.compareTo(threshold) < 0) {
                    throw ApiException.unprocessable("当前效价低于最低放行效价", rounded, threshold);
                }
                repo.insertEvidence(new BatchRepository.EvidenceRow(0L, batchKey, OP_RELEASE, now,
                        model.version(), releaseInputs(batch, computedAt), raw.toPlainString(),
                        rounded.toPlainString(), threshold.toPlainString(), now));
            }
            repo.insertApproval(new BatchRepository.ApprovalRow(0L, batchKey, req.commandKey(),
                    actor, role.name(), seq, now));
            repo.updateStatus(batchKey, newStatus.name());
            ApprovalResponse body = new ApprovalResponse(batchKey, actor, role, seq, newStatus,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 召回：RELEASED、SPLIT 或 MERGED 批次可召回，召回后进入 RECALLED 并不再出现在可用批次查询中。
     * 召回提交后其全部后代立即不可用（排除出可用查询、禁止新增检验/批准/拆分/合批），
     * 但后代自身状态与既有检验、批准记录不改写，也不为后代补写召回记录。
     */
    public StoredResponse recall(String batchKey, String actorId, RecallRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        Map<String, Object> fp = new TreeMap<>();
        fp.put("op", "recall");
        fp.put("batchKey", batchKey);
        fp.put("actor", actor);
        fp.put("reason", req.reason());
        return executeIdempotent(CMD_RECALL, req.commandKey(), fingerprint(fp), () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status != BatchStatus.RELEASED && status != BatchStatus.SPLIT
                    && status != BatchStatus.MERGED) {
                throw ApiException.conflict(
                        "批次状态 " + status + " 不允许召回，仅 RELEASED、SPLIT 或 MERGED 可召回");
            }
            // 逐行锁定全部后代（按业务键排序保证锁顺序确定）：与后代上的检验/批准/拆分/合批
            // 互斥，按事务提交顺序裁决——召回先提交则后代新操作看到召回并返回 422。
            List<String> descendants = descendantKeys(batchKey);
            Collections.sort(descendants);
            for (String descendantKey : descendants) {
                repo.findBatchForUpdate(descendantKey);
            }
            String now = now();
            repo.insertRecall(new BatchRepository.RecallRow(0L, batchKey, req.commandKey(),
                    actor, req.reason(), now));
            repo.updateStatus(batchKey, BatchStatus.RECALLED.name());
            RecallResponse body = new RecallResponse(batchKey, actor, req.reason(),
                    BatchStatus.RECALLED, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 当前可用批次：排除已召回（RECALLED）、已拆分（SPLIT）、已合批（MERGED）批次，
     * 以及任一祖先被召回的后代批次；后代自身状态不改写。
     */
    public List<BatchResponse> listAvailable() {
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        return repo.findAvailableBatches().stream()
                .filter(b -> !BatchStatus.SPLIT.name().equals(b.status()))
                .filter(b -> !BatchStatus.MERGED.name().equals(b.status()))
                .filter(b -> recalledAncestor(b.batchKey(), parentsOf, recalled).isEmpty())
                .map(this::toBatchResponse)
                .toList();
    }

    /**
     * 批次完整历史：批次概要 + 全部检验 + 全部批准 + 召回记录 + 全部效价证据，
     * 历史不因召回而删除或改写，证据不可回写。
     */
    public BatchHistoryResponse history(String batchKey) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<String> required = repo.findRequiredTests(batchKey);
        List<BatchRepository.TestRow> testRows = repo.findTests(batchKey);
        // 按提交顺序推导每条检验结果落定后的批次状态快照：FAIL→REJECTED；
        // 必做项全部 PASS 时该条→PENDING_RELEASE；其余→QUARANTINED。
        List<TestResultResponse> tests = new java.util.ArrayList<>(testRows.size());
        for (BatchRepository.TestRow t : testRows) {
            tests.add(toTestResponse(t, snapshotAfter(t, testRows, required).name()));
        }
        List<ApprovalResponse> approvals = repo.findApprovals(batchKey).stream()
                .map(a -> {
                    // 历史快照：第一笔批准落定 RELEASE_REVIEW，第二笔落定 RELEASED；不随后续召回改写
                    BatchStatus snapshot = a.seq() == 1
                            ? BatchStatus.RELEASE_REVIEW
                            : BatchStatus.RELEASED;
                    return new ApprovalResponse(a.batchKey(), a.actorId(), ApprovalRole.valueOf(a.role()),
                            a.seq(), snapshot, Instant.parse(a.createdAt()));
                })
                .toList();
        RecallResponse recall = repo.findRecall(batchKey)
                .map(r -> new RecallResponse(r.batchKey(), r.actorId(), r.reason(),
                        BatchStatus.RECALLED, Instant.parse(r.createdAt())))
                .orElse(null);
        List<PotencyEvidenceResponse> evidence = repo.findEvidence(batchKey).stream()
                .map(this::toEvidenceResponse)
                .toList();
        return new BatchHistoryResponse(toBatchResponse(batch), tests, approvals, recall, evidence);
    }

    /**
     * 批次效价证据查询：按写入顺序返回，只读不改状态。
     */
    public List<PotencyEvidenceResponse> listPotencyEvidence(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return repo.findEvidence(batchKey).stream().map(this::toEvidenceResponse).toList();
    }

    /**
     * 注册新版效价模型：版本按当前最大版本 +1 分配；模型锁内与放行/拆分/合批按提交顺序裁决。
     */
    public StoredResponse registerModel(PotencyModelRequest req) {
        String roundingMode = req.roundingMode().trim();
        if (!ALLOWED_ROUNDING_MODES.contains(roundingMode)) {
            throw ApiException.badRequest("roundingMode 必须为 HALF_UP/HALF_EVEN/DOWN/UP");
        }
        Map<String, Object> fp = new TreeMap<>();
        fp.put("op", "potency-model");
        fp.put("scale", req.scale());
        fp.put("roundingMode", roundingMode);
        return executeIdempotent(CMD_MODEL, req.commandKey(), fingerprint(fp), () -> {
            repo.lockPotencyModel();
            BatchRepository.ModelRow latest = latestModel();
            int version = latest.version() + 1;
            String now = now();
            repo.insertModel(new BatchRepository.ModelRow(0L, version, req.scale(), roundingMode,
                    req.commandKey(), now));
            PotencyModelResponse body = new PotencyModelResponse(version, req.scale(), roundingMode,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 全部效价模型版本，按版本升序；只读不改状态。
     */
    public List<PotencyModelResponse> listModels() {
        return repo.findModels().stream()
                .map(m -> new PotencyModelResponse(m.version(), m.scale(), m.roundingMode(),
                        Instant.parse(m.createdAt())))
                .toList();
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时重试，读取已提交结果。
     */
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
                // 并发同事务键冲突：回滚后重试，读取对方已提交的命令快照或业务结果
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    /**
     * 查询命令快照：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     */
    private Optional<StoredResponse> loggedResponse(String type, String commandKey,
                                                    String fingerprint) {
        var existing = repo.findCommand(type, commandKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("commandKey 已以不同参数使用: " + commandKey);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    private boolean allRequiredPassed(String batchKey, List<String> required) {
        Set<String> passed = new HashSet<>();
        for (BatchRepository.TestRow t : repo.findTests(batchKey)) {
            if (TestOutcome.PASS.name().equals(t.outcome())) {
                passed.add(t.testItem());
            }
        }
        return passed.containsAll(required);
    }

    private BatchResponse toBatchResponse(BatchRepository.BatchRow row) {
        return new BatchResponse(row.batchKey(), row.productCode(), row.batchNo(),
                Instant.parse(row.producedAt()), BatchStatus.valueOf(row.status()),
                repo.findRequiredTests(row.batchKey()), Instant.parse(row.createdAt()),
                dec(row.initialPotency()), dec(row.minReleasePotency()), dec(row.decayRatePerDay()),
                dec(row.quantity()), row.potencyEpochAt() == null ? null : Instant.parse(row.potencyEpochAt()));
    }

    private TestResultResponse toTestResponse(BatchRepository.TestRow row, String batchStatus) {
        return new TestResultResponse(row.batchKey(), row.testKey(), row.testItem(),
                TestOutcome.valueOf(row.outcome()), row.inspector(),
                BatchStatus.valueOf(batchStatus), Instant.parse(row.createdAt()));
    }

    private PotencyEvidenceResponse toEvidenceResponse(BatchRepository.EvidenceRow row) {
        try {
            return new PotencyEvidenceResponse(row.id(), row.batchKey(), row.operation(),
                    Instant.parse(row.computedAt()), row.modelVersion(),
                    objectMapper.readTree(row.inputs()), new BigDecimal(row.rawPotency()),
                    new BigDecimal(row.roundedPotency()),
                    row.threshold() == null ? null : new BigDecimal(row.threshold()),
                    Instant.parse(row.createdAt()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("证据输入快照解析失败", e);
        }
    }

    /**
     * 推导某条检验结果提交落定后的批次状态快照（按检验结果提交顺序）：
     * FAIL→REJECTED；此前已有 PASS 已覆盖全部必做项→PENDING_RELEASE；其余→QUARANTINED。
     */
    private BatchStatus snapshotAfter(BatchRepository.TestRow target,
                                      List<BatchRepository.TestRow> ordered,
                                      List<String> required) {
        if (TestOutcome.FAIL.name().equals(target.outcome())) {
            return BatchStatus.REJECTED;
        }
        Set<String> passed = new HashSet<>();
        for (BatchRepository.TestRow t : ordered) {
            if (TestOutcome.PASS.name().equals(t.outcome())) {
                passed.add(t.testItem());
            }
            if (t.id() == target.id()) {
                break;
            }
        }
        return passed.containsAll(required)
                ? BatchStatus.PENDING_RELEASE
                : BatchStatus.QUARANTINED;
    }

    private ApprovalRole parseRole(String roleHeader) {
        if (roleHeader == null || roleHeader.isBlank()) {
            throw ApiException.badRequest("X-Approval-Role 不能为空");
        }
        try {
            return ApprovalRole.valueOf(roleHeader.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("X-Approval-Role 必须为 QUALITY 或 OPERATIONS");
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    /**
     * 构造命令指纹：以操作类型、操作者、规范化集合及全部影响结果的字段组成规范化 JSON
     * （TreeMap 键序稳定、集合先排序、数值统一为去尾零纯数字符串），再取 SHA-256；
     * 不使用未转义分隔符拼接，避免字段值含分隔符造成碰撞。
     */
    private String fingerprint(Object canonical) {
        String json = toJson(canonical);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
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
     * 拆分：仅当前可用的 RELEASED 批次可一次拆成 2～5 个全新子批。
     * 子批继承产品编码、生产 UTC 时间与必做检验项，初始 QUARANTINED，
     * 不继承检验或批准记录；父批置为 SPLIT 不再可用。
     * 父批已登记效价时：每个子批必须声明正数数量且全部子批数量之和等于父批数量（权重守恒，
     * 否则整次 422 并携带实际值/要求值/差额）；子批初始效价为父批在拆分时刻的效价
     * （按当前模型版本舍入并固化 SPLIT 证据），衰减起算时刻为拆分时刻。
     * 创建全部子批、血缘关系与父批状态在同一事务提交；任一子批键已存在或重复，整次 409 且父批不变。
     */
    public StoredResponse split(String parentKey, SplitRequest req) {
        List<SplitRequest.ChildSpec> children = req.children();
        List<String> childKeys = children.stream().map(SplitRequest.ChildSpec::batchKey).toList();
        if (new HashSet<>(childKeys).size() != childKeys.size()) {
            throw ApiException.conflict("子批 batchKey 在请求内重复");
        }
        Map<String, Object> fp = new TreeMap<>();
        fp.put("op", "split");
        fp.put("parentKey", parentKey);
        fp.put("children", children.stream().map(c -> {
            Map<String, Object> m = new TreeMap<>();
            m.put("batchKey", c.batchKey());
            m.put("batchNo", c.batchNo());
            m.put("quantity", decimalText(c.quantity()));
            return m;
        }).toList());
        return executeIdempotent(CMD_SPLIT, req.commandKey(), fingerprint(fp), () -> {
            BatchRepository.BatchRow parent = repo.findBatchForUpdate(parentKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + parentKey));
            // 父批行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交，
            // 此时须重放首次结果而不是因父批已 SPLIT 误判冲突
            var logged = loggedResponse(CMD_SPLIT, req.commandKey(), fingerprint(fp));
            if (logged.isPresent()) {
                return logged.get();
            }
            assertNoRecalledAncestor(parentKey);
            if (!BatchStatus.RELEASED.name().equals(parent.status())) {
                throw ApiException.conflict(
                        "批次状态 " + parent.status() + " 不允许拆分，仅当前可用的 RELEASED 批次可拆分");
            }
            for (String childKey : childKeys) {
                if (repo.findBatch(childKey).isPresent()) {
                    throw ApiException.conflict("子批 batchKey 已存在: " + childKey);
                }
            }
            boolean potent = parent.initialPotency() != null;
            if (potent) {
                BigDecimal sum = BigDecimal.ZERO;
                for (SplitRequest.ChildSpec child : children) {
                    if (child.quantity() == null) {
                        throw ApiException.badRequest("父批已登记效价，子批必须声明数量: "
                                + child.batchKey());
                    }
                    if (child.quantity().signum() <= 0) {
                        throw ApiException.unprocessable("子批数量必须为正: " + child.batchKey());
                    }
                    sum = sum.add(child.quantity());
                }
                BigDecimal parentQuantity = dec(parent.quantity());
                if (sum.compareTo(parentQuantity) != 0) {
                    throw ApiException.unprocessable("子批数量之和与父批数量不守恒", sum, parentQuantity);
                }
            } else {
                for (SplitRequest.ChildSpec child : children) {
                    if (child.quantity() != null) {
                        throw ApiException.badRequest("父批未登记效价，子批不得声明数量: "
                                + child.batchKey());
                    }
                }
            }
            String now = now();
            // 父批已登记效价时在模型锁内读取一致模型版本，计算拆分时刻父批效价
            BatchRepository.ModelRow model = null;
            BigDecimal parentPotency = null;
            if (potent) {
                repo.lockPotencyModel();
                model = latestModel();
                BigDecimal raw = PotencyCalculator.currentPotency(dec(parent.initialPotency()),
                        dec(parent.decayRatePerDay()), Instant.parse(parent.potencyEpochAt()),
                        Instant.parse(now));
                parentPotency = PotencyCalculator.round(raw, model.scale(), model.roundingMode());
            }
            List<String> required = repo.findRequiredTests(parentKey);
            List<SplitResponse.SplitChild> childBodies = new ArrayList<>(children.size());
            for (int i = 0; i < children.size(); i++) {
                SplitRequest.ChildSpec spec = children.get(i);
                repo.insertBatch(new BatchRepository.BatchRow(0L, spec.batchKey(),
                        parent.productCode(), spec.batchNo(), parent.producedAt(),
                        BatchStatus.QUARANTINED.name(), now,
                        potent ? text(spec.quantity()) : null,
                        potent ? parentPotency.toPlainString() : null,
                        potent ? parent.minReleasePotency() : null,
                        potent ? parent.decayRatePerDay() : null,
                        potent ? now : null));
                for (int j = 0; j < required.size(); j++) {
                    repo.insertRequiredTest(spec.batchKey(), required.get(j), j + 1);
                }
                repo.insertLineage(new BatchRepository.LineageRow(0L, parentKey, spec.batchKey(),
                        i + 1, now));
                if (potent) {
                    repo.insertEvidence(new BatchRepository.EvidenceRow(0L, spec.batchKey(),
                            OP_SPLIT, now, model.version(),
                            parentInputs(parentKey, parentPotency, spec.quantity()),
                            parentPotency.toPlainString(), parentPotency.toPlainString(),
                            null, now));
                }
                childBodies.add(new SplitResponse.SplitChild(spec.batchKey(), spec.batchNo(),
                        parent.productCode(), Instant.parse(parent.producedAt()),
                        BatchStatus.QUARANTINED, required, Instant.parse(now),
                        potent ? spec.quantity() : null,
                        potent ? parentPotency : null,
                        potent ? dec(parent.minReleasePotency()) : null,
                        potent ? dec(parent.decayRatePerDay()) : null,
                        potent ? Instant.parse(now) : null));
            }
            repo.updateStatus(parentKey, BatchStatus.SPLIT.name());
            SplitResponse body = new SplitResponse(parentKey, BatchStatus.SPLIT, childBodies,
                    Instant.parse(now), potent ? model.version() : null);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 合批：2～5 个当前可用的 RELEASED 且已登记效价的父批整体并入一个全新子批。
     * 先验证完整后态（父批存在/已检验放行/未登记效价或效价不合格/权重不守恒/产品编码不一致
     * 分别返回 404/422/409），再在一个事务内提交子批、血缘、父批状态与证据；任一父批
     * 在合批时刻效价低于其最低放行效价则整次回滚，不留下部分结果。
     * 子批初始效价为所有父批在合批时刻效价的数量加权平均（按当前模型版本舍入），
     * 衰减率为父批衰减率的数量加权平均，最低放行效价取各父批最大值，起算时刻为合批时刻。
     */
    public StoredResponse merge(MergeRequest req) {
        List<String> parentKeys = req.parents().stream()
                .map(MergeRequest.ParentSpec::batchKey).toList();
        if (new HashSet<>(parentKeys).size() != parentKeys.size()) {
            throw ApiException.badRequest("父批 batchKey 在请求内重复");
        }
        if (req.quantity().signum() <= 0) {
            throw ApiException.unprocessable("合批子批数量必须为正", req.quantity(), BigDecimal.ZERO);
        }
        for (MergeRequest.ParentSpec parent : req.parents()) {
            if (parent.weight().signum() <= 0) {
                throw ApiException.unprocessable("父批权重必须为正: " + parent.batchKey());
            }
        }
        // 规范化父批集合（按 batchKey 排序）：指纹、锁顺序与血缘 seq 均以此为准
        List<MergeRequest.ParentSpec> normalized = req.parents().stream()
                .sorted(Comparator.comparing(MergeRequest.ParentSpec::batchKey))
                .toList();
        Map<String, Object> fp = new TreeMap<>();
        fp.put("op", "merge");
        fp.put("batchKey", req.batchKey());
        fp.put("batchNo", req.batchNo());
        fp.put("quantity", decimalText(req.quantity()));
        fp.put("parents", normalized.stream().map(p -> {
            Map<String, Object> m = new TreeMap<>();
            m.put("batchKey", p.batchKey());
            m.put("weight", decimalText(p.weight()));
            return m;
        }).toList());
        return executeIdempotent(CMD_MERGE, req.commandKey(), fingerprint(fp), () -> {
            repo.findBatch(req.batchKey()).ifPresent(b -> {
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            });
            // 按业务键排序逐行锁定全部父批：与父批上的检验/批准/召回/拆分互斥，按提交顺序裁决
            Map<String, BatchRepository.BatchRow> parentRows = new HashMap<>();
            for (MergeRequest.ParentSpec parent : normalized) {
                parentRows.put(parent.batchKey(), repo.findBatchForUpdate(parent.batchKey())
                        .orElseThrow(() -> ApiException.notFound("父批次不存在: " + parent.batchKey())));
            }
            // 父批行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = loggedResponse(CMD_MERGE, req.commandKey(), fingerprint(fp));
            if (logged.isPresent()) {
                return logged.get();
            }
            // 先验证完整后态：任一校验失败整次回滚，不产生部分结果
            String productCode = null;
            for (MergeRequest.ParentSpec parent : normalized) {
                BatchRepository.BatchRow row = parentRows.get(parent.batchKey());
                BatchStatus status = BatchStatus.valueOf(row.status());
                switch (status) {
                    case QUARANTINED, PENDING_RELEASE, RELEASE_REVIEW ->
                            throw ApiException.unprocessable(
                                    "父批次未完成检验放行: " + parent.batchKey() + " 状态 " + status);
                    case RELEASED -> {
                    }
                    default -> throw ApiException.conflict(
                            "父批次状态 " + status + " 不允许合批: " + parent.batchKey());
                }
                assertNoRecalledAncestor(parent.batchKey());
                if (row.initialPotency() == null) {
                    throw ApiException.unprocessable("父批次未登记效价: " + parent.batchKey());
                }
                BigDecimal registered = dec(row.quantity());
                if (parent.weight().compareTo(registered) != 0) {
                    throw ApiException.unprocessable(
                            "父批权重必须等于其登记数量: " + parent.batchKey(),
                            parent.weight(), registered);
                }
                if (productCode == null) {
                    productCode = row.productCode();
                } else if (!productCode.equals(row.productCode())) {
                    throw ApiException.unprocessable("父批产品编码不一致，不能合批: "
                            + parent.batchKey());
                }
            }
            BigDecimal sumWeights = normalized.stream()
                    .map(MergeRequest.ParentSpec::weight)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sumWeights.compareTo(req.quantity()) != 0) {
                throw ApiException.unprocessable("父批权重之和与子批数量不守恒",
                        sumWeights, req.quantity());
            }
            // 模型锁内读取一致模型版本，与模型修订按提交顺序裁决
            repo.lockPotencyModel();
            BatchRepository.ModelRow model = latestModel();
            String now = now();
            Instant computedAt = Instant.parse(now);
            List<BigDecimal> potencies = new ArrayList<>(normalized.size());
            List<BigDecimal> weights = new ArrayList<>(normalized.size());
            List<BigDecimal> rates = new ArrayList<>(normalized.size());
            BigDecimal threshold = null;
            List<MergeResponse.MergeParent> parentBodies = new ArrayList<>(normalized.size());
            List<Map<String, Object>> inputParents = new ArrayList<>(normalized.size());
            for (MergeRequest.ParentSpec parent : normalized) {
                BatchRepository.BatchRow row = parentRows.get(parent.batchKey());
                BigDecimal raw = PotencyCalculator.currentPotency(dec(row.initialPotency()),
                        dec(row.decayRatePerDay()), Instant.parse(row.potencyEpochAt()),
                        computedAt);
                BigDecimal potency = PotencyCalculator.round(raw, model.scale(),
                        model.roundingMode());
                BigDecimal parentThreshold = dec(row.minReleasePotency());
                if (potency.compareTo(parentThreshold) < 0) {
                    throw ApiException.unprocessable(
                            "父批次在合批时刻效价低于其最低放行效价: " + parent.batchKey(),
                            potency, parentThreshold);
                }
                potencies.add(potency);
                weights.add(parent.weight());
                rates.add(dec(row.decayRatePerDay()));
                threshold = threshold == null ? parentThreshold : threshold.max(parentThreshold);
                parentBodies.add(new MergeResponse.MergeParent(parent.batchKey(), parent.weight(),
                        potency, BatchStatus.MERGED));
                Map<String, Object> input = new TreeMap<>();
                input.put("batchKey", parent.batchKey());
                input.put("potency", potency.toPlainString());
                input.put("weight", parent.weight().toPlainString());
                inputParents.add(input);
            }
            BigDecimal initialPotency = PotencyCalculator.round(
                    PotencyCalculator.weightedAverage(potencies, weights),
                    model.scale(), model.roundingMode());
            BigDecimal decayRate = PotencyCalculator.round(
                    PotencyCalculator.weightedAverage(rates, weights),
                    model.scale(), model.roundingMode());
            // 必做检验项为全部父批必做项的并集（按父批规范化顺序首次出现去重）
            Set<String> requiredUnion = new LinkedHashSet<>();
            for (MergeRequest.ParentSpec parent : normalized) {
                requiredUnion.addAll(repo.findRequiredTests(parent.batchKey()));
            }
            List<String> required = List.copyOf(requiredUnion);
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(), productCode,
                    req.batchNo(), now, BatchStatus.QUARANTINED.name(), now,
                    text(req.quantity()), initialPotency.toPlainString(), threshold.toPlainString(),
                    decayRate.toPlainString(), now));
            for (int i = 0; i < required.size(); i++) {
                repo.insertRequiredTest(req.batchKey(), required.get(i), i + 1);
            }
            for (int i = 0; i < normalized.size(); i++) {
                MergeRequest.ParentSpec parent = normalized.get(i);
                repo.insertMergeLineage(new BatchRepository.MergeLineageRow(0L, parent.batchKey(),
                        req.batchKey(), parent.weight().toPlainString(), i + 1, now));
                repo.updateStatus(parent.batchKey(), BatchStatus.MERGED.name());
            }
            Map<String, Object> inputs = new TreeMap<>();
            inputs.put("parents", inputParents);
            repo.insertEvidence(new BatchRepository.EvidenceRow(0L, req.batchKey(), OP_MERGE, now,
                    model.version(), toJson(inputs), initialPotency.toPlainString(),
                    initialPotency.toPlainString(), null, now));
            MergeResponse body = new MergeResponse(req.batchKey(), req.batchNo(), productCode,
                    BatchStatus.QUARANTINED, req.quantity(), initialPotency, threshold, decayRate,
                    parentBodies, model.version(), computedAt);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 祖先查询：从直接父批逐级向上到根（多父批时按血缘创建顺序广度优先展开），
     * 每项含批次自身状态及导致其不可用的召回祖先。
     */
    public List<LineageEntryResponse> listAncestors(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        List<LineageEntryResponse> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(batchKey);
        visited.add(batchKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String parent : parentsOf.getOrDefault(current, List.of())) {
                if (visited.add(parent)) {
                    result.add(toLineageEntry(parent, parentsOf, recalled));
                    queue.add(parent);
                }
            }
        }
        return result;
    }

    /**
     * 后代查询：按血缘关系创建顺序广度优先展开，每项含批次自身状态及导致其不可用的召回祖先。
     */
    public List<LineageEntryResponse> listDescendants(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        List<LineageEntryResponse> result = new ArrayList<>();
        for (String key : descendantKeys(batchKey)) {
            result.add(toLineageEntry(key, parentsOf, recalled));
        }
        return result;
    }

    /**
     * 若任一级祖先已被召回则抛 422：后代批次禁止新增检验、批准、拆分和合批。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        Optional<String> recalled = recalledAncestor(batchKey, childToParents(),
                new HashSet<>(repo.findRecalledKeys()));
        if (recalled.isPresent()) {
            throw ApiException.unprocessable(
                    "祖先批次 " + recalled.get() + " 已召回，禁止新增检验、批准、拆分和合批");
        }
    }

    /**
     * 沿父链向上广度优先查找最近的被直接召回（RECALLED）祖先；批次自身召回不算祖先召回。
     */
    private Optional<String> recalledAncestor(String batchKey, Map<String, List<String>> parentsOf,
                                              Set<String> recalled) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(batchKey);
        visited.add(batchKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String parent : parentsOf.getOrDefault(current, List.of())) {
                if (visited.add(parent)) {
                    if (recalled.contains(parent)) {
                        return Optional.of(parent);
                    }
                    queue.add(parent);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 全部血缘边（拆分 + 合批）的 子批→父批列表 映射；关系不可改写，只增不改。
     */
    private Map<String, List<String>> childToParents() {
        Map<String, List<String>> parentsOf = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            parentsOf.computeIfAbsent(row.childKey(), k -> new ArrayList<>()).add(row.parentKey());
        }
        for (BatchRepository.MergeLineageRow row : repo.findAllMergeLineage()) {
            parentsOf.computeIfAbsent(row.childKey(), k -> new ArrayList<>()).add(row.parentKey());
        }
        return parentsOf;
    }

    /**
     * 某批次的全部后代业务键，按血缘关系创建顺序广度优先展开（不含自身）。
     */
    private List<String> descendantKeys(String rootKey) {
        Map<String, List<String>> childrenOf = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            childrenOf.computeIfAbsent(row.parentKey(), k -> new ArrayList<>()).add(row.childKey());
        }
        for (BatchRepository.MergeLineageRow row : repo.findAllMergeLineage()) {
            childrenOf.computeIfAbsent(row.parentKey(), k -> new ArrayList<>()).add(row.childKey());
        }
        List<String> result = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String child : childrenOf.getOrDefault(current, List.of())) {
                result.add(child);
                queue.add(child);
            }
        }
        return result;
    }

    private LineageEntryResponse toLineageEntry(String batchKey,
                                                Map<String, List<String>> parentsOf,
                                                Set<String> recalled) {
        BatchRepository.BatchRow row = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return new LineageEntryResponse(row.batchKey(), row.batchNo(),
                BatchStatus.valueOf(row.status()),
                recalledAncestor(batchKey, parentsOf, recalled).orElse(null));
    }

    /**
     * 当前最新效价模型；schema 初始化保证至少存在版本 1。
     */
    private BatchRepository.ModelRow latestModel() {
        return repo.findLatestModel()
                .orElseThrow(() -> new IllegalStateException("效价模型未初始化"));
    }

    /**
     * 放行证据的输入快照：批次键、登记初始效价、衰减率、起算时刻与已衰减 UTC 整天数。
     */
    private String releaseInputs(BatchRepository.BatchRow batch, Instant computedAt) {
        Map<String, Object> inputs = new TreeMap<>();
        inputs.put("batchKey", batch.batchKey());
        inputs.put("initialPotency", batch.initialPotency());
        inputs.put("decayRatePerDay", batch.decayRatePerDay());
        inputs.put("potencyEpochAt", batch.potencyEpochAt());
        inputs.put("elapsedDays",
                PotencyCalculator.elapsedUtcDays(Instant.parse(batch.potencyEpochAt()), computedAt));
        return toJson(inputs);
    }

    /**
     * 拆分证据的输入快照：父批键、拆分时刻父批效价与子批权重。
     */
    private String parentInputs(String parentKey, BigDecimal parentPotency, BigDecimal weight) {
        Map<String, Object> parent = new TreeMap<>();
        parent.put("batchKey", parentKey);
        parent.put("potency", parentPotency.toPlainString());
        parent.put("weight", weight.toPlainString());
        Map<String, Object> inputs = new TreeMap<>();
        inputs.put("parents", List.of(parent));
        return toJson(inputs);
    }

    private static BigDecimal dec(String text) {
        return text == null ? null : new BigDecimal(text);
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /**
     * 指纹用数值规范化：去尾零纯数字符串，避免 1.0 与 1.00 或科学计数法表示造成指纹漂移。
     */
    private static String decimalText(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
