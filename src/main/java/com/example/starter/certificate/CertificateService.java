package com.example.starter.certificate;

import com.example.starter.batch.ApiException;
import com.example.starter.batch.BatchRepository;
import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.Fingerprints;
import com.example.starter.batch.IdempotentExecutor;
import com.example.starter.batch.StoredResponse;
import com.example.starter.certificate.dto.BatchBindRequest;
import com.example.starter.certificate.dto.BatchBindResponse;
import com.example.starter.certificate.dto.BatchCertificateResponse;
import com.example.starter.certificate.dto.BindCertificateRequest;
import com.example.starter.certificate.dto.BindingResponse;
import com.example.starter.certificate.dto.CertificateBindingInfo;
import com.example.starter.certificate.dto.DeclarationDetailResponse;
import com.example.starter.certificate.dto.DeclarationVersionResponse;
import com.example.starter.certificate.dto.DiagnosticsResponse;
import com.example.starter.certificate.dto.MergeRequest;
import com.example.starter.certificate.dto.MergeResponse;
import com.example.starter.certificate.dto.PublishDeclarationRequest;
import com.example.starter.certificate.dto.ReleaseSnapshotInfo;
import com.example.starter.certificate.dto.WithdrawDeclarationRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 合格证声明与批次绑定核心服务。
 *
 * <p>并发策略：声明发布按物料加 JVM 分段锁串行化重叠校验；撤回、绑定、合批、放行
 * 在事务内对声明版本行 SELECT ... FOR UPDATE，按事务提交顺序裁决，放行读取一致声明。
 * commandKey 幂等经 {@link IdempotentExecutor}：同键同参重放返回首次响应快照，
 * 同键改参 409，失败不占键。所有时间统一 UTC，有效区间左闭右开。
 */
@Service
public class CertificateService {

    private static final String CMD_PUBLISH = "PUBLISH_DECLARATION";
    private static final String CMD_WITHDRAW = "WITHDRAW_DECLARATION";
    private static final String CMD_BIND = "BIND_CERTIFICATE";
    private static final String CMD_BATCH_BIND = "BATCH_BIND_CERTIFICATE";
    private static final String CMD_MERGE = "MERGE_BATCH";

    private static final int PUBLISH_LOCK_STRIPES = 64;

    private final CertificateRepository certRepo;
    private final BatchRepository batchRepo;
    private final IdempotentExecutor idempotent;
    private final ObjectMapper objectMapper;
    private final Object[] publishLocks = new Object[PUBLISH_LOCK_STRIPES];

    public CertificateService(CertificateRepository certRepo, BatchRepository batchRepo,
                              IdempotentExecutor idempotent, ObjectMapper objectMapper) {
        this.certRepo = certRepo;
        this.batchRepo = batchRepo;
        this.idempotent = idempotent;
        this.objectMapper = objectMapper;
        for (int i = 0; i < PUBLISH_LOCK_STRIPES; i++) {
            publishLocks[i] = new Object();
        }
    }

    /**
     * 放行校验结果：放行时点固化的声明版本与匹配前缀。
     */
    public record ReleaseDecision(String declarationKey, int declarationVersion,
                                  String matchedPrefix) {
    }

    /**
     * 发布声明版本：有效区间必须合法（起点早于终点，左闭右开），否则 422；
     * 同物料同优先级的 ACTIVE 声明前缀不得重叠（任一前缀互为前缀即重叠），否则 422；
     * 同业务键同版本已存在时，内容一致返回 200 原快照，内容不同返回 409。
     */
    public StoredResponse publish(String actorId, PublishDeclarationRequest req) {
        String actor = requireActor(actorId);
        List<String> prefixes = req.prefixes().stream().map(String::trim).toList();
        if (new HashSet<>(prefixes).size() != prefixes.size()) {
            throw ApiException.badRequest("prefixes 存在重复前缀");
        }
        if (!req.validFrom().isBefore(req.validTo())) {
            throw ApiException.unprocessable("INVALID_INTERVAL",
                    "有效区间非法：起点 " + req.validFrom() + " 必须早于终点 " + req.validTo()
                            + "（左闭右开）");
        }
        List<String> parts = new ArrayList<>(List.of("publish", actor, req.declarationKey(),
                String.valueOf(req.version()), req.supplierId(), req.materialCode(),
                String.valueOf(req.priority()), req.validFrom().toString(),
                req.validTo().toString()));
        parts.addAll(prefixes);
        String fingerprint = Fingerprints.of(parts);
        // 同物料发布串行化：重叠校验与插入在同一临界区，按提交顺序裁决
        synchronized (publishLock(req.materialCode())) {
            return idempotent.execute(CMD_PUBLISH, req.commandKey(), fingerprint, () -> {
                var existing = certRepo.findDeclaration(req.declarationKey(), req.version());
                if (existing.isPresent()) {
                    DeclarationRowView row = new DeclarationRowView(existing.get(),
                            certRepo.findPrefixes(req.declarationKey(), req.version()));
                    if (sameContent(row, req, prefixes)) {
                        return new StoredResponse(200, toJson(toVersionResponse(row)));
                    }
                    throw ApiException.conflict("声明版本已存在且内容不同: "
                            + req.declarationKey() + " v" + req.version());
                }
                for (CertificateRepository.DeclarationRow other
                        : certRepo.findActiveByMaterial(req.materialCode())) {
                    // 同业务键的历史版本天然前缀重叠，豁免；不同优先级由优先级裁决，豁免
                    if (other.declarationKey().equals(req.declarationKey())
                            || other.priority() != req.priority()) {
                        continue;
                    }
                    for (String otherPrefix
                            : certRepo.findPrefixes(other.declarationKey(), other.version())) {
                        for (String prefix : prefixes) {
                            if (prefix.startsWith(otherPrefix) || otherPrefix.startsWith(prefix)) {
                                throw ApiException.unprocessable("PREFIX_OVERLAP",
                                        "前缀 " + prefix + " 与有效声明 " + other.declarationKey()
                                                + " v" + other.version() + " 的前缀 " + otherPrefix
                                                + " 重叠且优先级相同（" + req.priority() + "）");
                            }
                        }
                    }
                }
                String now = now();
                certRepo.insertDeclaration(new CertificateRepository.DeclarationRow(0L,
                        req.declarationKey(), req.version(), req.supplierId(), req.materialCode(),
                        req.priority(), req.validFrom().toString(), req.validTo().toString(),
                        DeclarationStatus.ACTIVE.name(), req.commandKey(), now, null, null));
                for (int i = 0; i < prefixes.size(); i++) {
                    certRepo.insertPrefix(req.declarationKey(), req.version(), prefixes.get(i),
                            i + 1);
                }
                DeclarationRowView created = new DeclarationRowView(
                        certRepo.findDeclaration(req.declarationKey(), req.version()).orElseThrow(),
                        prefixes);
                return new StoredResponse(201, toJson(toVersionResponse(created)));
            });
        }
    }

    /**
     * 撤回声明版本：只影响后续绑定与放行，不改写历史绑定与放行快照；
     * 已撤回版本再次撤回（新命令键）返回 409。
     */
    public StoredResponse withdraw(String declarationKey, int version, String actorId,
                                   WithdrawDeclarationRequest req) {
        String actor = requireActor(actorId);
        String fingerprint = Fingerprints.of("withdraw", actor, declarationKey,
                String.valueOf(version));
        return idempotent.execute(CMD_WITHDRAW, req.commandKey(), fingerprint, () -> {
            CertificateRepository.DeclarationRow row = certRepo
                    .findDeclarationForUpdate(declarationKey, version)
                    .orElseThrow(() -> ApiException.notFound(
                            "声明版本不存在: " + declarationKey + " v" + version));
            if (DeclarationStatus.WITHDRAWN.name().equals(row.status())) {
                throw ApiException.conflict("声明版本已撤回: " + declarationKey + " v" + version);
            }
            String now = now();
            certRepo.markWithdrawn(declarationKey, version, actor, now);
            CertificateRepository.DeclarationRow updated = certRepo
                    .findDeclaration(declarationKey, version).orElseThrow();
            return new StoredResponse(200, toJson(toVersionResponse(
                    new DeclarationRowView(updated,
                            certRepo.findPrefixes(declarationKey, version)))));
        });
    }
    /**
     * 单批次绑定：声明必须 ACTIVE 且在绑定时点处于有效区间，物料与批号前缀必须匹配，
     * 否则 422；批次不存在 404。已绑定同版本返回 200 原绑定；放行前可更换绑定（200），
     * 已放行批次不得更换（409）。
     */
    public StoredResponse bind(String batchKey, String actorId, BindCertificateRequest req) {
        String actor = requireActor(actorId);
        String fingerprint = Fingerprints.of("bind", actor, batchKey, req.declarationKey(),
                String.valueOf(req.version()));
        return idempotent.execute(CMD_BIND, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var existing = certRepo.findBinding(batchKey);
            if (existing.isPresent()
                    && existing.get().declarationKey().equals(req.declarationKey())
                    && existing.get().declarationVersion() == req.version()) {
                return new StoredResponse(200, toJson(toBindingResponse(existing.get())));
            }
            if (existing.isPresent() && certRepo.findReleaseSnapshot(batchKey).isPresent()) {
                throw ApiException.conflict("批次已放行，不得更换合格证绑定: " + batchKey);
            }
            CertificateRepository.DeclarationRow decl = certRepo
                    .findDeclarationForUpdate(req.declarationKey(), req.version())
                    .orElseThrow(() -> ApiException.notFound(
                            "声明版本不存在: " + req.declarationKey() + " v" + req.version()));
            String now = now();
            assertEffective(decl, Instant.parse(now));
            String matched = matchOrThrow(decl, batch);
            CertificateRepository.BindingRow row = new CertificateRepository.BindingRow(0L,
                    batchKey, req.declarationKey(), req.version(), matched, actor, now,
                    req.commandKey());
            if (existing.isPresent()) {
                certRepo.updateBinding(row);
                return new StoredResponse(200, toJson(toBindingResponse(row)));
            }
            certRepo.insertBinding(row);
            return new StoredResponse(201, toJson(toBindingResponse(row)));
        });
    }

    /**
     * 批量绑定：先校验完整最终状态（全部批次存在、全部声明有效且匹配、无放行后换绑冲突），
     * 再在同一事务中提交全部绑定；任一失效或冲突整次回滚，查询不到半成品绑定。
     * 响应携带请求项数 requestedCount（要求值）与实际绑定数 boundCount（实际值）。
     */
    public StoredResponse batchBind(String actorId, BatchBindRequest req) {
        String actor = requireActor(actorId);
        List<BatchBindRequest.Item> items = req.items();
        List<String> batchKeys = items.stream().map(BatchBindRequest.Item::batchKey).toList();
        if (new HashSet<>(batchKeys).size() != batchKeys.size()) {
            throw ApiException.badRequest("items 内 batchKey 重复");
        }
        // 规范化对象集合：绑定项按 (batchKey, declarationKey, version) 排序后进入指纹
        List<BatchBindRequest.Item> sortedItems = items.stream()
                .sorted(Comparator.comparing(BatchBindRequest.Item::batchKey)
                        .thenComparing(BatchBindRequest.Item::declarationKey)
                        .thenComparing(BatchBindRequest.Item::version))
                .toList();
        List<String> parts = new ArrayList<>(List.of("batch-bind", actor));
        for (BatchBindRequest.Item item : sortedItems) {
            parts.add(item.batchKey());
            parts.add(item.declarationKey());
            parts.add(String.valueOf(item.version()));
        }
        String fingerprint = Fingerprints.of(parts);
        return idempotent.execute(CMD_BATCH_BIND, req.commandKey(), fingerprint, () -> {
            Map<String, BatchRepository.BatchRow> batches = new HashMap<>();
            for (String key : batchKeys.stream().sorted().toList()) {
                batches.put(key, batchRepo.findBatchForUpdate(key)
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + key)));
            }
            Map<String, CertificateRepository.DeclarationRow> declarations = new HashMap<>();
            for (BatchBindRequest.Item item : sortedItems) {
                String ref = item.declarationKey() + "@" + item.version();
                if (!declarations.containsKey(ref)) {
                    declarations.put(ref, certRepo
                            .findDeclarationForUpdate(item.declarationKey(), item.version())
                            .orElseThrow(() -> ApiException.notFound("声明版本不存在: "
                                    + item.declarationKey() + " v" + item.version())));
                }
            }
            String now = now();
            Instant at = Instant.parse(now);
            // 第一阶段：完整校验所有绑定项的最终状态，不做任何写入
            List<CertificateRepository.BindingRow> toInsert = new ArrayList<>();
            List<CertificateRepository.BindingRow> toUpdate = new ArrayList<>();
            List<CertificateRepository.BindingRow> satisfied = new ArrayList<>();
            for (BatchBindRequest.Item item : sortedItems) {
                var existing = certRepo.findBinding(item.batchKey());
                if (existing.isPresent()
                        && existing.get().declarationKey().equals(item.declarationKey())
                        && existing.get().declarationVersion() == item.version()) {
                    satisfied.add(existing.get());
                    continue;
                }
                if (existing.isPresent()
                        && certRepo.findReleaseSnapshot(item.batchKey()).isPresent()) {
                    throw ApiException.conflict(
                            "批次已放行，不得更换合格证绑定: " + item.batchKey());
                }
                CertificateRepository.DeclarationRow decl = declarations
                        .get(item.declarationKey() + "@" + item.version());
                assertEffective(decl, at);
                String matched = matchOrThrow(decl, batches.get(item.batchKey()));
                CertificateRepository.BindingRow row = new CertificateRepository.BindingRow(0L,
                        item.batchKey(), item.declarationKey(), item.version(), matched, actor,
                        now, req.commandKey());
                (existing.isPresent() ? toUpdate : toInsert).add(row);
            }
            // 第二阶段：同事务提交全部绑定
            toInsert.forEach(certRepo::insertBinding);
            toUpdate.forEach(certRepo::updateBinding);
            List<CertificateRepository.BindingRow> all = new ArrayList<>();
            all.addAll(satisfied);
            all.addAll(toInsert);
            all.addAll(toUpdate);
            List<BindingResponse> bindings = all.stream()
                    .sorted(Comparator.comparing(CertificateRepository.BindingRow::batchKey))
                    .map(this::toBindingResponse)
                    .toList();
            BatchBindResponse body = new BatchBindResponse(items.size(), bindings.size(),
                    bindings, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 合批：将 2～5 个当前可用的 RELEASED 父批合并为一个全新子批。
     * 重新校验全部父批物料与批号是否被目标声明版本覆盖（任一不满足 422 整次回滚）；
     * 子批继承父批必做检验项并集（按请求顺序去重），初始 QUARANTINED 并绑定目标声明；
     * 父批置为 MERGED 终态不再可用。全部写入在同一事务提交。
     */
    public StoredResponse merge(String actorId, MergeRequest req) {
        String actor = requireActor(actorId);
        List<String> parents = req.parentBatchKeys();
        if (new HashSet<>(parents).size() != parents.size()) {
            throw ApiException.badRequest("parentBatchKeys 存在重复父批");
        }
        if (parents.contains(req.childBatchKey())) {
            throw ApiException.badRequest("childBatchKey 不得与任一父批相同");
        }
        List<String> parts = new ArrayList<>(List.of("merge", actor, req.childBatchKey(),
                req.childBatchNo(), req.declarationKey(), String.valueOf(req.declarationVersion())));
        parts.addAll(parents);
        String fingerprint = Fingerprints.of(parts);
        return idempotent.execute(CMD_MERGE, req.commandKey(), fingerprint, () -> {
            if (batchRepo.findBatch(req.childBatchKey()).isPresent()) {
                throw ApiException.conflict("childBatchKey 已存在: " + req.childBatchKey());
            }
            // 按业务键排序加锁，保证并发合批/召回/放行的锁顺序确定
            Map<String, BatchRepository.BatchRow> locked = new HashMap<>();
            for (String key : parents.stream().sorted().toList()) {
                locked.put(key, batchRepo.findBatchForUpdate(key)
                        .orElseThrow(() -> ApiException.notFound("父批不存在: " + key)));
            }
            for (String key : parents) {
                BatchRepository.BatchRow parent = locked.get(key);
                if (!BatchStatus.RELEASED.name().equals(parent.status())) {
                    throw ApiException.conflict("父批 " + key + " 状态 " + parent.status()
                            + " 不允许合批，仅当前可用的 RELEASED 批次可合批");
                }
                assertNoRecalledAncestor(key);
            }
            CertificateRepository.DeclarationRow decl = certRepo
                    .findDeclarationForUpdate(req.declarationKey(), req.declarationVersion())
                    .orElseThrow(() -> ApiException.notFound("声明版本不存在: "
                            + req.declarationKey() + " v" + req.declarationVersion()));
            String now = now();
            assertEffective(decl, Instant.parse(now));
            List<String> declPrefixes = certRepo.findPrefixes(req.declarationKey(),
                    req.declarationVersion());
            // 重新校验全部父批物料与批号
            for (String key : parents) {
                BatchRepository.BatchRow parent = locked.get(key);
                if (!decl.materialCode().equals(parent.productCode())) {
                    throw ApiException.unprocessable("MATERIAL_MISMATCH",
                            "父批 " + key + " 物料 " + parent.productCode() + " 与声明物料 "
                                    + decl.materialCode() + " 不匹配");
                }
                longestMatch(declPrefixes, parent.batchNo()).orElseThrow(() ->
                        ApiException.unprocessable("PREFIX_MISMATCH",
                                "父批 " + key + " 批号 " + parent.batchNo()
                                        + " 不匹配声明允许前缀 " + declPrefixes));
            }
            String childMatched = longestMatch(declPrefixes, req.childBatchNo())
                    .orElseThrow(() -> ApiException.unprocessable("PREFIX_MISMATCH",
                            "子批批号 " + req.childBatchNo() + " 不匹配声明允许前缀 " + declPrefixes));
            LinkedHashSet<String> required = new LinkedHashSet<>();
            for (String key : parents) {
                required.addAll(batchRepo.findRequiredTests(key));
            }
            batchRepo.insertBatch(new BatchRepository.BatchRow(0L, req.childBatchKey(),
                    decl.materialCode(), req.childBatchNo(), now, BatchStatus.QUARANTINED.name(),
                    now));
            int seq = 1;
            for (String item : required) {
                batchRepo.insertRequiredTest(req.childBatchKey(), item, seq++);
            }
            certRepo.insertBinding(new CertificateRepository.BindingRow(0L, req.childBatchKey(),
                    req.declarationKey(), req.declarationVersion(), childMatched, actor, now,
                    req.commandKey()));
            List<MergeResponse.MergeParent> parentBodies = new ArrayList<>(parents.size());
            for (int i = 0; i < parents.size(); i++) {
                certRepo.insertMerge(new CertificateRepository.MergeRow(0L, req.childBatchKey(),
                        parents.get(i), i + 1, now));
                batchRepo.updateStatus(parents.get(i), BatchStatus.MERGED.name());
                parentBodies.add(new MergeResponse.MergeParent(parents.get(i),
                        BatchStatus.MERGED.name()));
            }
            MergeResponse body = new MergeResponse(req.childBatchKey(),
                    BatchStatus.QUARANTINED.name(), decl.materialCode(), parents.size(),
                    parentBodies, req.declarationKey(), req.declarationVersion(), childMatched,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }
    /**
     * 放行门禁：批次必须已绑定声明版本，且该版本在放行时点 ACTIVE、处于有效区间、
     * 物料与批号前缀匹配，否则 422。声明版本行 SELECT ... FOR UPDATE，
     * 与并发撤回按事务提交顺序裁决，放行读取一致声明。
     */
    public ReleaseDecision validateRelease(String batchKey, Instant releasedAt) {
        CertificateRepository.BindingRow binding = certRepo.findBinding(batchKey)
                .orElseThrow(() -> ApiException.unprocessable("BINDING_REQUIRED",
                        "批次未绑定合格证声明，不能放行: " + batchKey));
        CertificateRepository.DeclarationRow decl = certRepo
                .findDeclarationForUpdate(binding.declarationKey(), binding.declarationVersion())
                .orElseThrow(() -> ApiException.unprocessable("DECLARATION_MISSING",
                        "绑定的声明版本不存在: " + binding.declarationKey() + " v"
                                + binding.declarationVersion()));
        assertEffective(decl, releasedAt);
        BatchRepository.BatchRow batch = batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        String matched = matchOrThrow(decl, batch);
        return new ReleaseDecision(decl.declarationKey(), decl.version(), matched);
    }

    /**
     * 写入放行快照：固化声明版本、匹配前缀与放行时点，随后续操作不改写。
     */
    public void recordReleaseSnapshot(String batchKey, ReleaseDecision decision,
                                      String releasedAt) {
        certRepo.insertReleaseSnapshot(new CertificateRepository.ReleaseSnapshotRow(0L, batchKey,
                decision.declarationKey(), decision.declarationVersion(),
                decision.matchedPrefix(), releasedAt));
    }

    /**
     * 拆分继承：子批继承父批绑定的声明版本引用；匹配前缀按子批批号重新计算，
     * 子批号不匹配任何前缀时 matchedPrefix 为 null（放行时将被 422 拦截）。
     */
    public void inheritBinding(String parentKey, String childKey, String childBatchNo,
                               String now, String commandKey) {
        var parentBinding = certRepo.findBinding(parentKey);
        if (parentBinding.isEmpty()) {
            return;
        }
        CertificateRepository.BindingRow parent = parentBinding.get();
        List<String> prefixes = certRepo.findPrefixes(parent.declarationKey(),
                parent.declarationVersion());
        String matched = longestMatch(prefixes, childBatchNo).orElse(null);
        certRepo.insertBinding(new CertificateRepository.BindingRow(0L, childKey,
                parent.declarationKey(), parent.declarationVersion(), matched, parent.boundBy(),
                now, commandKey));
    }

    /**
     * 声明明细：业务键 + 全部版本，按版本号升序；不存在返回 404。
     */
    public DeclarationDetailResponse declarationDetail(String declarationKey) {
        List<CertificateRepository.DeclarationRow> versions = certRepo.findVersions(declarationKey);
        if (versions.isEmpty()) {
            throw ApiException.notFound("声明不存在: " + declarationKey);
        }
        List<DeclarationVersionResponse> bodies = versions.stream()
                .map(row -> toVersionResponse(new DeclarationRowView(row,
                        certRepo.findPrefixes(row.declarationKey(), row.version()))))
                .toList();
        return new DeclarationDetailResponse(declarationKey, bodies);
    }

    /**
     * 匹配诊断：对给定物料与批号，在评估时点逐个评估全部声明版本。
     * 候选稳定排序：匹配项在前（优先级降序、业务键升序、版本降序），
     * 未匹配项在后（业务键升序、版本降序）；胜者为优先级最高的匹配版本。
     */
    public DiagnosticsResponse diagnostics(String materialCode, String batchNo, String atParam) {
        Instant at = parseAt(atParam);
        List<DiagnosticsResponse.Candidate> matched = new ArrayList<>();
        List<DiagnosticsResponse.Candidate> unmatched = new ArrayList<>();
        for (CertificateRepository.DeclarationRow row : certRepo.findAllDeclarations()) {
            List<String> reasons = new ArrayList<>();
            if (!row.materialCode().equals(materialCode)) {
                reasons.add("MATERIAL_MISMATCH");
            }
            if (DeclarationStatus.WITHDRAWN.name().equals(row.status())) {
                reasons.add("WITHDRAWN");
            }
            Instant from = Instant.parse(row.validFrom());
            Instant to = Instant.parse(row.validTo());
            if (at.isBefore(from)) {
                reasons.add("NOT_YET_VALID");
            } else if (!at.isBefore(to)) {
                reasons.add("EXPIRED");
            }
            String matchedPrefix = longestMatch(
                    certRepo.findPrefixes(row.declarationKey(), row.version()), batchNo)
                    .orElse(null);
            if (matchedPrefix == null) {
                reasons.add("PREFIX_MISMATCH");
            }
            DiagnosticsResponse.Candidate candidate = new DiagnosticsResponse.Candidate(
                    row.declarationKey(), row.version(), row.priority(), row.status(),
                    reasons.isEmpty(), reasons.isEmpty() ? matchedPrefix : null, reasons);
            (reasons.isEmpty() ? matched : unmatched).add(candidate);
        }
        matched.sort(Comparator.comparing(DiagnosticsResponse.Candidate::priority).reversed()
                .thenComparing(DiagnosticsResponse.Candidate::declarationKey)
                .thenComparing(Comparator.comparing(DiagnosticsResponse.Candidate::version)
                        .reversed()));
        unmatched.sort(Comparator.comparing(DiagnosticsResponse.Candidate::declarationKey)
                .thenComparing(Comparator.comparing(DiagnosticsResponse.Candidate::version)
                        .reversed()));
        DiagnosticsResponse.Winner winner = null;
        boolean ambiguous = false;
        if (!matched.isEmpty()) {
            DiagnosticsResponse.Candidate top = matched.get(0);
            winner = new DiagnosticsResponse.Winner(top.declarationKey(), top.version(),
                    top.priority(), top.matchedPrefix());
            ambiguous = matched.size() > 1 && matched.get(1).priority() == top.priority();
        }
        List<DiagnosticsResponse.Candidate> candidates = new ArrayList<>();
        candidates.addAll(matched);
        candidates.addAll(unmatched);
        return new DiagnosticsResponse(materialCode, batchNo, at,
                candidates.size(), matched.size(), ambiguous, winner, candidates);
    }

    /**
     * 批次合格证明细：当前绑定与放行快照；未绑定/未放行时对应字段为 null。
     */
    public BatchCertificateResponse batchCertificate(String batchKey) {
        BatchRepository.BatchRow batch = batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return new BatchCertificateResponse(batchKey, batch.status(), certificateInfo(batchKey),
                releaseSnapshotInfo(batchKey));
    }

    /**
     * 批次历史用绑定信息；未绑定返回 null。绑定字段为绑定时刻固化证据，
     * declarationStatus 为声明版本当前实时状态。
     */
    public CertificateBindingInfo certificateInfo(String batchKey) {
        return certRepo.findBinding(batchKey)
                .map(binding -> {
                    String declarationStatus = certRepo.findDeclaration(binding.declarationKey(),
                                    binding.declarationVersion())
                            .map(CertificateRepository.DeclarationRow::status)
                            .orElse(null);
                    return new CertificateBindingInfo(binding.declarationKey(),
                            binding.declarationVersion(), binding.matchedPrefix(),
                            binding.boundBy(), Instant.parse(binding.boundAt()),
                            declarationStatus);
                })
                .orElse(null);
    }

    /**
     * 批次历史用放行快照；未放行返回 null。快照为放行时点固化证据，不改写。
     */
    public ReleaseSnapshotInfo releaseSnapshotInfo(String batchKey) {
        return certRepo.findReleaseSnapshot(batchKey)
                .map(row -> new ReleaseSnapshotInfo(row.declarationKey(), row.declarationVersion(),
                        row.matchedPrefix(), Instant.parse(row.releasedAt())))
                .orElse(null);
    }

    /**
     * 校验声明版本在给定时点有效：未撤回且时点落在 [validFrom, validTo) 内。
     * 失败信息携带实际时点与要求的有效区间。
     */
    private void assertEffective(CertificateRepository.DeclarationRow decl, Instant at) {
        if (DeclarationStatus.WITHDRAWN.name().equals(decl.status())) {
            throw ApiException.unprocessable("DECLARATION_WITHDRAWN",
                    "声明版本已撤回: " + decl.declarationKey() + " v" + decl.version());
        }
        Instant from = Instant.parse(decl.validFrom());
        Instant to = Instant.parse(decl.validTo());
        if (at.isBefore(from)) {
            throw ApiException.unprocessable("DECLARATION_NOT_YET_VALID",
                    "时点 " + at + " 早于声明有效区间起点；要求区间 [" + from + ", " + to + "): "
                            + decl.declarationKey() + " v" + decl.version());
        }
        if (!at.isBefore(to)) {
            throw ApiException.unprocessable("DECLARATION_EXPIRED",
                    "时点 " + at + " 不在声明有效区间内；要求区间 [" + from + ", " + to + "): "
                            + decl.declarationKey() + " v" + decl.version());
        }
    }

    /**
     * 校验声明物料与批次产品编码一致、批号匹配允许前缀；返回实际匹配的最长前缀。
     */
    private String matchOrThrow(CertificateRepository.DeclarationRow decl,
                                BatchRepository.BatchRow batch) {
        if (!decl.materialCode().equals(batch.productCode())) {
            throw ApiException.unprocessable("MATERIAL_MISMATCH",
                    "批次物料 " + batch.productCode() + " 与声明物料 " + decl.materialCode()
                            + " 不匹配: " + batch.batchKey());
        }
        return longestMatch(certRepo.findPrefixes(decl.declarationKey(), decl.version()),
                batch.batchNo())
                .orElseThrow(() -> ApiException.unprocessable("PREFIX_MISMATCH",
                        "批次 " + batch.batchKey() + " 批号 " + batch.batchNo()
                                + " 不匹配声明 " + decl.declarationKey() + " v" + decl.version()
                                + " 的允许前缀"));
    }

    /**
     * 最长前缀匹配：返回覆盖批号的最长允许前缀；无匹配返回空。
     */
    private Optional<String> longestMatch(List<String> prefixes, String batchNo) {
        return prefixes.stream()
                .filter(batchNo::startsWith)
                .max(Comparator.comparingInt(String::length));
    }

    /**
     * 若任一级祖先已被召回则抛 422：合批父批不得带有召回祖先。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.LineageRow row : batchRepo.findAllLineage()) {
            parentOf.put(row.childKey(), row.parentKey());
        }
        java.util.Set<String> recalled = new HashSet<>(batchRepo.findRecalledKeys());
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                throw ApiException.unprocessable(
                        "祖先批次 " + current + " 已召回，禁止合批");
            }
        }
    }

    private boolean sameContent(DeclarationRowView view, PublishDeclarationRequest req,
                                List<String> prefixes) {
        CertificateRepository.DeclarationRow row = view.row();
        return row.supplierId().equals(req.supplierId())
                && row.materialCode().equals(req.materialCode())
                && row.priority() == req.priority()
                && row.validFrom().equals(req.validFrom().toString())
                && row.validTo().equals(req.validTo().toString())
                && view.prefixes().equals(prefixes);
    }

    private DeclarationVersionResponse toVersionResponse(DeclarationRowView view) {
        CertificateRepository.DeclarationRow row = view.row();
        return new DeclarationVersionResponse(row.declarationKey(), row.version(),
                row.supplierId(), row.materialCode(), view.prefixes(), row.priority(),
                Instant.parse(row.validFrom()), Instant.parse(row.validTo()), row.status(),
                Instant.parse(row.createdAt()),
                row.withdrawnAt() == null ? null : Instant.parse(row.withdrawnAt()),
                row.withdrawnBy());
    }

    private BindingResponse toBindingResponse(CertificateRepository.BindingRow row) {
        return new BindingResponse(row.batchKey(), row.declarationKey(),
                row.declarationVersion(), row.matchedPrefix(), row.boundBy(),
                Instant.parse(row.boundAt()));
    }

    private Instant parseAt(String atParam) {
        if (atParam == null || atParam.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(atParam.trim());
        } catch (Exception e) {
            throw ApiException.badRequest("at 必须为 ISO-8601 UTC instant: " + atParam);
        }
    }

    private Object publishLock(String materialCode) {
        return publishLocks[Math.floorMod(materialCode.hashCode(), PUBLISH_LOCK_STRIPES)];
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return actorId.trim();
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private String now() {
        return Instant.now().toString();
    }

    /**
     * 声明版本行与其前缀列表的视图。
     */
    private record DeclarationRowView(CertificateRepository.DeclarationRow row,
                                      List<String> prefixes) {
    }
}