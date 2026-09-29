package com.example.starter.batch;

import com.example.starter.batch.dto.BindCertificatesRequest;
import com.example.starter.batch.dto.BindCertificatesResponse;
import com.example.starter.batch.dto.CertificateBindingResponse;
import com.example.starter.batch.dto.CertificateDiagnosticsResponse;
import com.example.starter.batch.dto.DeclarationResponse;
import com.example.starter.batch.dto.PublishDeclarationRequest;
import com.example.starter.batch.dto.ReleaseCertificateSnapshotResponse;
import com.example.starter.batch.dto.WithdrawDeclarationRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 合格证声明与批次门禁核心服务。
 *
 * <p>声明按物料发布不可变版本，撤回只置状态；批次放行前必须绑定与其物料、批号前缀匹配，
 * 且在放行时点处于有效期（左闭右开，UTC）的声明。批量绑定先完整校验全部最终状态，
 * 再与命令快照在同一事务提交，任一失效整次回滚。
 */
@Service
public class CertificateService {

    public static final String CMD_PUBLISH = "PUBLISH_DECLARATION";
    public static final String CMD_WITHDRAW = "WITHDRAW_DECLARATION";
    public static final String CMD_BIND = "BIND_CERTIFICATE";

    /**
     * 门禁单物料评估结果。
     */
    public record GateResult(String materialCode, BatchRepository.BindingRow binding,
                             BatchRepository.DeclarationRow declaration, String matchedPrefix,
                             boolean satisfied, String reasonCode) {
    }

    private final BatchRepository repo;
    private final CommandExecutor commands;
    private final UtcClock clock;
    private final ObjectMapper objectMapper;

    public CertificateService(BatchRepository repo, CommandExecutor commands, UtcClock clock,
                              ObjectMapper objectMapper) {
        this.repo = repo;
        this.commands = commands;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布声明版本：物料内版本自增；区间非法、请求内前缀重复、与同优先级 ACTIVE 声明前缀重叠返回 422。
     */
    public StoredResponse publish(PublishDeclarationRequest req, String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        List<String> prefixes = req.allowedBatchNoPrefixes().stream().map(String::trim).toList();
        if (CertificateRules.hasDuplicate(prefixes)) {
            throw ApiException.unprocessable("PREFIX_OVERLAP_SAME_PRIORITY",
                    "同一声明内允许批号前缀不得重复");
        }
        if (CertificateRules.isInvalidInterval(req.validFrom(), req.validTo())) {
            throw ApiException.unprocessable("DECLARATION_INVALID_INTERVAL",
                    "有效期非法：validTo 必须严格晚于 validFrom（UTC，左闭右开）");
        }
        // 规范化集合：前缀按字典序参与指纹，避免请求顺序不同被误判为异参
        List<String> sortedPrefixes = prefixes.stream().sorted().toList();
        List<String> scalars = List.of("publish", actor, req.declarationKey(), req.materialCode(),
                Integer.toString(req.priority()), req.validFrom().toString(), req.validTo().toString());
        String fingerprint = Fingerprints.sha256(scalars, List.of(sortedPrefixes));

        return commands.execute(CMD_PUBLISH, req.commandKey(), fingerprint, () -> {
            if (repo.findDeclaration(req.declarationKey()).isPresent()) {
                throw ApiException.conflict("declarationKey 已存在: " + req.declarationKey());
            }
            // 锁定该物料全部既有声明（按 id 排序确定锁顺序）：串行化同物料并发发布，
            // 保证版本自增与同优先级重叠判定按提交顺序一致。
            List<BatchRepository.DeclarationRow> existing = repo.findDeclarationsByMaterial(
                    req.materialCode());
            for (BatchRepository.DeclarationRow row : existing) {
                repo.findDeclarationForUpdate(row.declarationKey());
            }
            Set<String> newPrefixSet = new LinkedHashSet<>(prefixes);
            List<Set<String>> sameLevel = repo.findActivePrefixGroupsByPriority(
                            req.materialCode(), req.priority()).stream()
                    .map(LinkedHashSet::new)
                    .collect(Collectors.toList());
            String[] overlap = CertificateRules.findSamePriorityOverlap(newPrefixSet, sameLevel);
            if (overlap != null) {
                throw ApiException.unprocessable("PREFIX_OVERLAP_SAME_PRIORITY",
                        "前缀 " + overlap[0] + " 与同物料同优先级的有效声明前缀重叠");
            }
            int version = repo.maxDeclarationVersion(req.materialCode()) + 1;
            String now = clock.now().toString();
            repo.insertDeclaration(new BatchRepository.DeclarationRow(0L, req.declarationKey(),
                    req.materialCode(), version, req.priority(),
                    req.validFrom().toString(), req.validTo().toString(),
                    DeclarationStatus.ACTIVE.name(), actor, null, null, now));
            for (int i = 0; i < prefixes.size(); i++) {
                repo.insertPrefix(new BatchRepository.PrefixRow(0L, req.declarationKey(),
                        req.materialCode(), prefixes.get(i), i + 1));
            }
            DeclarationResponse body = new DeclarationResponse(req.declarationKey(),
                    req.materialCode(), version, req.priority(), req.validFrom(), req.validTo(),
                    DeclarationStatus.ACTIVE.name(), prefixes, actor, null, null,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 撤回声明：仅 ACTIVE 可撤回；撤回只影响之后的放行与绑定，历史快照不改写。
     */
    public StoredResponse withdraw(String declarationKey, String actorId,
                                   WithdrawDeclarationRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        String fingerprint = Fingerprints.sha256("withdraw", declarationKey, actor, req.reason());
        return commands.execute(CMD_WITHDRAW, req.commandKey(), fingerprint, () -> {
            BatchRepository.DeclarationRow declaration = repo.findDeclarationForUpdate(declarationKey)
                    .orElseThrow(() -> ApiException.notFound("声明不存在: " + declarationKey));
            if (DeclarationStatus.WITHDRAWN.name().equals(declaration.status())) {
                throw ApiException.conflict("声明已撤回，不能重复撤回: " + declarationKey);
            }
            String now = clock.now().toString();
            repo.markDeclarationWithdrawn(declarationKey, actor, now);
            DeclarationResponse body = toResponse(
                    new BatchRepository.DeclarationRow(declaration.id(), declaration.declarationKey(),
                            declaration.materialCode(), declaration.version(), declaration.priority(),
                            declaration.validFrom(), declaration.validTo(),
                            DeclarationStatus.WITHDRAWN.name(), declaration.publishedBy(),
                            actor, now, declaration.publishedAt()));
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 单批次绑定：内部复用批量绑定的「先全部校验再整事务提交」语义。
     */
    public StoredResponse bindOne(String batchKey, String actorId,
                                  com.example.starter.batch.dto.BindCertificateRequest req) {
        BindCertificatesRequest bulk = new BindCertificatesRequest(req.commandKey(),
                List.of(new BindCertificatesRequest.BindingSpec(batchKey, req.declarationKey())));
        StoredResponse response = bindMany(actorId, bulk);
        // 单条入口返回该条绑定明细，而非批量包装体
        BindCertificatesResponse parsed = fromJson(response.body(), BindCertificatesResponse.class);
        CertificateBindingResponse item = parsed.bindings().get(0);
        return new StoredResponse(response.status(), toJson(item));
    }

    /**
     * 批量绑定：先校验完整最终状态（存在性、物料、前缀、撤回、有效期），全部通过后在同一事务插入。
     * 任一条失效整次回滚，查询不到任何半成品绑定。
     */
    public StoredResponse bindMany(String actorId, BindCertificatesRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        List<BindCertificatesRequest.BindingSpec> specs = req.bindings();
        List<String> pairKeys = specs.stream()
                .map(s -> s.batchKey() + "#" + s.declarationKey())
                .toList();
        if (new LinkedHashSet<>(pairKeys).size() != pairKeys.size()) {
            throw ApiException.conflict("批量绑定中存在重复的 (batchKey, declarationKey) 条目");
        }
        // 幂等指纹：操作者 + 操作类型 + 规范化对象集合（先按批次键再按声明键排序），长度前缀编码不依赖分隔符
        List<String> normalizedPairs = specs.stream()
                .map(s -> List.of(s.batchKey(), s.declarationKey()))
                .sorted(Comparator.comparing((List<String> p) -> p.get(0))
                        .thenComparing(p -> p.get(1)))
                .flatMap(List::stream)
                .toList();
        String fingerprint = Fingerprints.sha256(List.of("bind", actor), List.of(normalizedPairs));

        return commands.execute(CMD_BIND, req.commandKey(), fingerprint, () -> {
            Instant at = clock.now();
            // 确定锁顺序：批次按业务键排序、声明按业务键排序，避免多对象事务死锁
            List<String> batchOrder = specs.stream().map(BindCertificatesRequest.BindingSpec::batchKey)
                    .distinct().sorted().toList();
            Map<String, BatchRepository.BatchRow> batches = new LinkedHashMap<>();
            for (String key : batchOrder) {
                BatchRepository.BatchRow batch = repo.findBatchForUpdate(key)
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
                batches.put(key, batch);
            }
            List<String> declarationOrder = specs.stream()
                    .map(BindCertificatesRequest.BindingSpec::declarationKey)
                    .distinct().sorted().toList();
            Map<String, BatchRepository.DeclarationRow> declarations = new LinkedHashMap<>();
            for (String key : declarationOrder) {
                BatchRepository.DeclarationRow declaration = repo.findDeclarationForUpdate(key)
                        .orElseThrow(() -> ApiException.notFound("声明不存在: " + key));
                declarations.put(key, declaration);
            }

            List<BatchRepository.BindingRow> rows = new ArrayList<>(specs.size());
            List<CertificateBindingResponse> bodies = new ArrayList<>(specs.size());
            for (BindCertificatesRequest.BindingSpec spec : specs) {
                BatchRepository.BatchRow batch = batches.get(spec.batchKey());
                BatchRepository.DeclarationRow declaration = declarations.get(spec.declarationKey());
                String matchedPrefix = validateBinding(batch, declaration, at);
                List<BatchRepository.BindingRow> existing = repo.findBindings(batch.batchKey());
                if (existing.stream().anyMatch(b -> b.materialCode().equals(declaration.materialCode()))) {
                    throw ApiException.conflict("批次 " + batch.batchKey() + " 的物料 "
                            + declaration.materialCode() + " 已绑定合格证声明，放行前不可重复绑定");
                }
                rows.add(new BatchRepository.BindingRow(0L, batch.batchKey(),
                        declaration.materialCode(), declaration.declarationKey(),
                        declaration.version(), matchedPrefix, null, actor, at.toString()));
                bodies.add(new CertificateBindingResponse(batch.batchKey(),
                        declaration.materialCode(), declaration.declarationKey(),
                        declaration.version(), matchedPrefix, null, actor, at));
            }
            // 全部条目校验通过后才统一插入（同一事务）
            for (BatchRepository.BindingRow row : rows) {
                repo.insertBinding(row);
            }
            BindCertificatesResponse body = new BindCertificatesResponse(bodies, bodies.size(), at);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 校验单条绑定的完整最终状态，返回实际命中的批号前缀；任一不满足抛 422。
     */
    private String validateBinding(BatchRepository.BatchRow batch,
                                   BatchRepository.DeclarationRow declaration, Instant at) {
        BatchStatus status = BatchStatus.valueOf(batch.status());
        if (status != BatchStatus.QUARANTINED && status != BatchStatus.PENDING_RELEASE
                && status != BatchStatus.RELEASE_REVIEW) {
            throw ApiException.conflict("批次状态 " + status + " 不允许绑定合格证，仅放行前可绑定");
        }
        List<String> requiredMaterials = requiredMaterials(batch);
        if (!requiredMaterials.contains(declaration.materialCode())) {
            throw ApiException.unprocessable("MATERIAL_MISMATCH",
                    "批次 " + batch.batchKey() + " 要求物料 " + requiredMaterials
                            + "，声明物料为 " + declaration.materialCode());
        }
        if (DeclarationStatus.WITHDRAWN.name().equals(declaration.status())) {
            throw ApiException.unprocessable("DECLARATION_WITHDRAWN",
                    "声明已撤回: " + declaration.declarationKey());
        }
        Instant from = Instant.parse(declaration.validFrom());
        Instant to = Instant.parse(declaration.validTo());
        if (at.isBefore(from)) {
            throw ApiException.unprocessable("DECLARATION_NOT_YET_VALID",
                    "声明尚未生效：绑定时点 " + at + " 早于有效期起点 " + from);
        }
        if (!at.isBefore(to)) {
            throw ApiException.unprocessable("DECLARATION_EXPIRED",
                    "声明已失效：绑定时点 " + at + " 不在有效期（终点不含）" + to + " 之前");
        }
        List<String> prefixes = repo.findPrefixes(declaration.declarationKey());
        // 普通/拆分批次用自身批号；合批产物用该物料来源父批的批号重新校验
        List<String> targetBatchNos = sourceBatchNos(batch, declaration.materialCode());
        requireCommonMatchingPrefix(batch.batchKey(), targetBatchNos, prefixes);
        return CertificateRules.matchingPrefix(targetBatchNos.get(0), prefixes);
    }

    /**
     * 前缀校验的目标批号集合：
     * 普通批次/拆分产物为自身批号（单元素）；
     * 合批产物为指定物料的全部来源父批批号（按合批请求 seq 顺序）。
     */
    private List<String> sourceBatchNos(BatchRepository.BatchRow batch, String materialCode) {
        List<BatchRepository.MergeLineageRow> merges =
                repo.findMergeLineageByChild(batch.batchKey());
        if (merges.isEmpty()) {
            return List.of(batch.batchNo());
        }
        List<String> nos = merges.stream()
                .map(m -> repo.findBatch(m.parentKey()).orElseThrow(
                        () -> ApiException.notFound("合批父批不存在: " + m.parentKey())))
                .filter(p -> p.productCode().equals(materialCode))
                .map(BatchRepository.BatchRow::batchNo)
                .toList();
        if (nos.isEmpty()) {
            throw ApiException.unprocessable("MATERIAL_MISMATCH",
                    "合批产物 " + batch.batchKey() + " 不包含物料 " + materialCode + " 的父批");
        }
        return nos;
    }

    /**
     * 要求每个目标批号都命中声明允许前缀，且命中同一前缀；否则抛 PREFIX_MISMATCH 422。
     * 返回共同命中前缀。
     */
    private String requireCommonMatchingPrefix(String batchKey, List<String> targetBatchNos,
                                               List<String> prefixes) {
        String common = null;
        for (String no : targetBatchNos) {
            String matched = CertificateRules.matchingPrefix(no, prefixes);
            if (matched == null) {
                throw ApiException.unprocessable("PREFIX_MISMATCH",
                        "批号 " + no + " 不命中声明允许前缀 " + prefixes + "（批次/合批来源: "
                                + batchKey + "）");
            }
            if (common == null) {
                common = matched;
            } else if (!common.equals(matched)) {
                throw ApiException.unprocessable("PREFIX_MISMATCH",
                        "同物料来源批号命中的允许前缀不一致: " + targetBatchNos);
            }
        }
        return common;
    }

    /**
     * 放行门禁：在放行事务内调用，锁定绑定声明做一致读，逐要求物料评估；
     * 任一物料未满足抛 422。返回通过评估的结果，供放行固化快照。
     */
    public List<GateResult> requireGateForRelease(String batchKey, Instant at) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<String> required = requiredMaterials(batch);
        List<GateResult> results = evaluate(batch, required, at, true);
        for (GateResult result : results) {
            if (!result.satisfied()) {
                throw ApiException.unprocessable(result.reasonCode(),
                        "物料 " + result.materialCode() + " 合格证门禁未满足: " + result.reasonCode());
            }
        }
        return results;
    }

    /**
     * 只读诊断：按当前 UTC 时点评估门禁，返回实际值/要求值/差额，不改变任何状态。
     */
    public CertificateDiagnosticsResponse diagnostics(String batchKey) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Instant at = clock.now();
        List<String> required = requiredMaterials(batch);
        List<GateResult> results = evaluate(batch, required, at, false);

        List<String> bound = results.stream()
                .filter(r -> r.binding() != null)
                .map(GateResult::materialCode)
                .sorted().toList();
        List<String> missing = required.stream()
                .filter(m -> !bound.contains(m))
                .sorted().toList();
        List<String> requiredSorted = required.stream().sorted().toList();
        boolean ready = results.stream().allMatch(GateResult::satisfied);

        List<CertificateDiagnosticsResponse.MaterialGateItem> items = results.stream()
                .sorted(Comparator.comparing(GateResult::materialCode))
                .map(r -> toGateItem(r, at))
                .toList();
        return new CertificateDiagnosticsResponse(batch.batchKey(), batch.batchNo(),
                List.of(batch.productCode()), at, ready, bound.size(), required.size(),
                missing.size(), requiredSorted, bound, missing, items);
    }

    private CertificateDiagnosticsResponse.MaterialGateItem toGateItem(GateResult r, Instant at) {
        BatchRepository.DeclarationRow d = r.declaration();
        Long startDelta = null;
        Long endDelta = null;
        boolean materialMatches = false;
        boolean prefixMatches = false;
        boolean active = false;
        if (d != null) {
            Instant from = Instant.parse(d.validFrom());
            Instant to = Instant.parse(d.validTo());
            startDelta = Duration.between(from, at).getSeconds();
            endDelta = Duration.between(at, to).getSeconds();
            materialMatches = d.materialCode().equals(r.materialCode());
            prefixMatches = r.matchedPrefix() != null
                    && !"PREFIX_MISMATCH".equals(r.reasonCode());
            active = DeclarationStatus.ACTIVE.name().equals(d.status())
                    && CertificateRules.isActiveAt(from, to, at);
        }
        return new CertificateDiagnosticsResponse.MaterialGateItem(r.materialCode(),
                d == null ? null : d.declarationKey(),
                d == null ? null : d.version(),
                d == null ? null : d.status(),
                r.matchedPrefix(), materialMatches, prefixMatches, active,
                startDelta, endDelta, r.satisfied(), r.reasonCode());
    }

    /**
     * 逐要求物料评估门禁。
     *
     * @param forUpdate 放行事务内为 true：对绑定声明加行锁，保证与撤回/发布按提交顺序一致读
     */
    private List<GateResult> evaluate(BatchRepository.BatchRow batch, List<String> required,
                                      Instant at, boolean forUpdate) {
        Map<String, BatchRepository.BindingRow> bindings = repo.findBindings(batch.batchKey()).stream()
                .collect(Collectors.toMap(BatchRepository.BindingRow::materialCode, b -> b,
                        (a, b) -> a, LinkedHashMap::new));
        // 放行时锁定涉及的声明行（按键排序），与撤回互斥
        if (forUpdate) {
            bindings.values().stream().map(BatchRepository.BindingRow::declarationKey)
                    .distinct().sorted()
                    .forEach(repo::findDeclarationForUpdate);
        }
        List<GateResult> results = new ArrayList<>(required.size());
        for (String material : required.stream().sorted().toList()) {
            BatchRepository.BindingRow binding = bindings.get(material);
            if (binding == null) {
                results.add(new GateResult(material, null, null, null, false, "NOT_BOUND"));
                continue;
            }
            BatchRepository.DeclarationRow declaration = forUpdate
                    ? repo.findDeclarationForUpdate(binding.declarationKey()).orElse(null)
                    : repo.findDeclaration(binding.declarationKey()).orElse(null);
            if (declaration == null) {
                results.add(new GateResult(material, binding, null, null, false, "NOT_BOUND"));
                continue;
            }
            String reason = evaluateOne(batch, binding, declaration, at);
            List<String> targetNos = sourceBatchNos(batch, material);
            List<String> prefixes = repo.findPrefixes(declaration.declarationKey());
            String matched = CertificateRules.matchingPrefix(targetNos.get(0), prefixes);
            results.add(new GateResult(material, binding, declaration, matched,
                    reason == null, reason == null ? "OK" : reason));
        }
        return results;
    }

    /**
     * 评估单个已绑定物料；返回 null 表示满足，否则返回原因码。
     */
    private String evaluateOne(BatchRepository.BatchRow batch,
                               BatchRepository.BindingRow binding,
                               BatchRepository.DeclarationRow declaration, Instant at) {
        if (!declaration.materialCode().equals(binding.materialCode())) {
            return "MATERIAL_MISMATCH";
        }
        List<String> prefixes = repo.findPrefixes(declaration.declarationKey());
        // 以来源批号（普通批次为自身批号；合批产物为该物料来源父批批号）重新校验前缀命中
        List<String> targetNos = sourceBatchNos(batch, binding.materialCode());
        String common = null;
        for (String no : targetNos) {
            String matched = CertificateRules.matchingPrefix(no, prefixes);
            if (matched == null) {
                return "PREFIX_MISMATCH";
            }
            if (common == null) {
                common = matched;
            } else if (!common.equals(matched)) {
                return "PREFIX_MISMATCH";
            }
        }
        if (DeclarationStatus.WITHDRAWN.name().equals(declaration.status())) {
            return "DECLARATION_WITHDRAWN";
        }
        Instant from = Instant.parse(declaration.validFrom());
        Instant to = Instant.parse(declaration.validTo());
        if (at.isBefore(from)) {
            return "NOT_YET_VALID";
        }
        if (!at.isBefore(to)) {
            return "EXPIRED";
        }
        return null;
    }

    /**
     * 批次要求覆盖的物料集合：合批产物为全部父批物料去重；普通批次为其自身物料。
     * 返回结果稳定排序。
     */
    public List<String> requiredMaterials(BatchRepository.BatchRow batch) {
        List<String> parentKeys = repo.findMergeLineageByChild(batch.batchKey()).stream()
                .map(BatchRepository.MergeLineageRow::parentKey)
                .toList();
        if (parentKeys.isEmpty()) {
            return List.of(batch.productCode());
        }
        return parentKeys.stream()
                .map(k -> repo.findBatch(k).orElseThrow(
                        () -> ApiException.notFound("合批父批不存在: " + k)))
                .map(BatchRepository.BatchRow::productCode)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 拆分继承：将父批全部绑定引用复制给子批（同声明版本、同命中前缀），记录继承来源。
     * 在拆分事务内调用。
     */
    public void inheritBindings(String parentKey, String childKey, String at) {
        for (BatchRepository.BindingRow parentBinding : repo.findBindings(parentKey)) {
            repo.insertBinding(new BatchRepository.BindingRow(0L, childKey,
                    parentBinding.materialCode(), parentBinding.declarationKey(),
                    parentBinding.version(), parentBinding.matchedPrefix(), parentKey,
                    parentBinding.boundBy(), at));
        }
    }

    /**
     * 写入放行固化快照（每个要求物料一行），在放行事务内调用。
     */
    public void persistReleaseSnapshots(String batchKey, Instant at, List<GateResult> gateResults) {
        for (GateResult result : gateResults) {
            BatchRepository.DeclarationRow d = result.declaration();
            repo.insertReleaseSnapshot(new BatchRepository.ReleaseSnapshotRow(0L, batchKey,
                    result.materialCode(), d.declarationKey(), d.version(), result.matchedPrefix(),
                    d.validFrom(), d.validTo(), at.toString()));
        }
    }

    /**
     * 批次已固化的放行快照，按物料字典序稳定返回；未放行为空列表。
     */
    public List<ReleaseCertificateSnapshotResponse> releaseSnapshots(String batchKey) {
        return repo.findReleaseSnapshots(batchKey).stream()
                .sorted(Comparator.comparing(BatchRepository.ReleaseSnapshotRow::materialCode))
                .map(r -> new ReleaseCertificateSnapshotResponse(r.batchKey(), r.materialCode(),
                        r.declarationKey(), r.version(), r.matchedPrefix(),
                        Instant.parse(r.validFrom()), Instant.parse(r.validTo()),
                        Instant.parse(r.releasedAt())))
                .toList();
    }

    /**
     * 批次当前绑定明细，按绑定提交顺序返回。
     */
    public List<CertificateBindingResponse> listBindings(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return repo.findBindings(batchKey).stream()
                .map(b -> new CertificateBindingResponse(b.batchKey(), b.materialCode(),
                        b.declarationKey(), b.version(), b.matchedPrefix(), b.inheritedFrom(),
                        b.boundBy(), Instant.parse(b.boundAt())))
                .toList();
    }

    public DeclarationResponse getDeclaration(String declarationKey) {
        return toResponse(repo.findDeclaration(declarationKey)
                .orElseThrow(() -> ApiException.notFound("声明不存在: " + declarationKey)));
    }

    public List<DeclarationResponse> listDeclarations(String materialCode) {
        if (materialCode == null || materialCode.isBlank()) {
            throw ApiException.badRequest("materialCode 不能为空");
        }
        return repo.findDeclarationsByMaterial(materialCode.trim()).stream()
                .map(this::toResponse)
                .toList();
    }

    private DeclarationResponse toResponse(BatchRepository.DeclarationRow row) {
        return new DeclarationResponse(row.declarationKey(), row.materialCode(), row.version(),
                row.priority(), Instant.parse(row.validFrom()), Instant.parse(row.validTo()),
                row.status(), repo.findPrefixes(row.declarationKey()), row.publishedBy(),
                row.withdrawnBy(),
                row.withdrawnAt() == null ? null : Instant.parse(row.withdrawnAt()),
                Instant.parse(row.publishedAt()));
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
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }
}
