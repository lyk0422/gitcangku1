package com.example.starter.batch;

import com.example.starter.batch.dto.BatchCertificatesResponse;
import com.example.starter.batch.dto.BindCertificatesRequest;
import com.example.starter.batch.dto.BindCertificatesResponse;
import com.example.starter.batch.dto.BindingResponse;
import com.example.starter.batch.dto.CertificateDiagnosticsResponse;
import com.example.starter.batch.dto.CertificateHistoryResponse;
import com.example.starter.batch.dto.CertificateResponse;
import com.example.starter.batch.dto.MergeBatchesRequest;
import com.example.starter.batch.dto.MergeBatchesResponse;
import com.example.starter.batch.dto.PublishCertificateRequest;
import com.example.starter.batch.dto.ReleaseSnapshotResponse;
import com.example.starter.batch.dto.WithdrawCertificateRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 批次合格证匹配核心服务：版本化声明的发布/撤回、批次绑定、合批重校验与放行门禁。
 *
 * <p>并发策略：发布/撤回/绑定/合批按事务提交顺序裁决；声明版本行 SELECT ... FOR UPDATE
 * 串行化撤回与放行读取，放行在同一事务内读到一致声明。commandKey 幂等经由
 * {@link IdempotentExecutor}：同键同参重放返回首次响应快照，同键改参 409，失败不占键。
 * 多对象写入先校验完整最终状态，再在一个事务中提交。
 */
@Service
public class CertificateService {

    public static final String CMD_PUBLISH = "PUBLISH_CERTIFICATE";
    public static final String CMD_WITHDRAW = "WITHDRAW_CERTIFICATE";
    public static final String CMD_BIND = "BIND_CERTIFICATE";
    public static final String CMD_MERGE = "MERGE_BATCH";

    private final CertificateRepository certs;
    private final BatchRepository batches;
    private final IdempotentExecutor idempotent;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CertificateService(CertificateRepository certs,
                              BatchRepository batches,
                              IdempotentExecutor idempotent,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.certs = certs;
        this.batches = batches;
        this.idempotent = idempotent;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 放行门禁的判定结果：放行时固化的声明版本与匹配前缀。
     */
    public record ReleaseChoice(String declarationKey, int version, String matchedPrefix) {
    }
    /**
     * 发布声明版本：有效区间必须合法（左闭右开，终点晚于起点），重叠前缀优先级不得相同；
     * (declarationKey, version) 全局唯一，重复 409。
     */
    public StoredResponse publish(String actorId, PublishCertificateRequest req) {
        String actor = requireActor(actorId);
        if (req.version() < 1) {
            throw ApiException.badRequest("version 必须 >= 1");
        }
        if (!req.validTo().isAfter(req.validFrom())) {
            throw ApiException.unprocessable("INVALID_INTERVAL",
                    "有效区间非法：validTo 必须晚于 validFrom（左闭右开）");
        }
        List<PublishCertificateRequest.PrefixSpec> prefixes = req.prefixes();
        Set<String> seen = new HashSet<>();
        for (PublishCertificateRequest.PrefixSpec spec : prefixes) {
            if (!seen.add(spec.prefix())) {
                throw ApiException.badRequest("prefixes 存在重复前缀: " + spec.prefix());
            }
        }
        for (int i = 0; i < prefixes.size(); i++) {
            for (int j = i + 1; j < prefixes.size(); j++) {
                String a = prefixes.get(i).prefix();
                String b = prefixes.get(j).prefix();
                boolean overlap = a.startsWith(b) || b.startsWith(a);
                if (overlap && prefixes.get(i).priority().equals(prefixes.get(j).priority())) {
                    throw ApiException.unprocessable("PREFIX_OVERLAP",
                            "前缀 " + a + " 与 " + b + " 重叠且优先级相同: " + prefixes.get(i).priority());
                }
            }
        }
        List<String> normalized = prefixes.stream()
                .map(p -> p.prefix() + "=" + p.priority())
                .sorted()
                .toList();
        List<String> parts = new ArrayList<>();
        parts.add("publish");
        parts.add(actor);
        parts.add(req.declarationKey());
        parts.add(String.valueOf(req.version()));
        parts.add(req.materialCode());
        parts.add(req.validFrom().toString());
        parts.add(req.validTo().toString());
        parts.addAll(normalized);
        String fingerprint = IdempotentExecutor.fingerprint(parts.toArray(new String[0]));
        return idempotent.execute(CMD_PUBLISH, req.commandKey(), fingerprint, () -> {
            certs.findDeclaration(req.declarationKey(), req.version()).ifPresent(d -> {
                throw ApiException.conflict(
                        "声明版本已存在: " + req.declarationKey() + "@" + req.version());
            });
            String now = now();
            certs.insertDeclaration(new CertificateRepository.DeclarationRow(0L, req.declarationKey(),
                    req.version(), req.materialCode(), req.validFrom().toString(),
                    req.validTo().toString(), DeclarationStatus.ACTIVE.name(), actor, now,
                    null, null));
            for (int i = 0; i < prefixes.size(); i++) {
                certs.insertPrefix(new CertificateRepository.PrefixRow(0L, req.declarationKey(),
                        req.version(), prefixes.get(i).prefix(), prefixes.get(i).priority(), i + 1));
            }
            CertificateResponse body = toResponse(
                    certs.findDeclaration(req.declarationKey(), req.version()).orElseThrow());
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 撤回声明版本：仅 ACTIVE 可撤回，重复撤回 409；撤回只影响后续绑定与放行，
     * 不改写已产生的绑定与放行快照。
     */
    public StoredResponse withdraw(String actorId, String declarationKey,
                                   WithdrawCertificateRequest req) {
        String actor = requireActor(actorId);
        String fingerprint = IdempotentExecutor.fingerprint("withdraw", actor, declarationKey,
                String.valueOf(req.version()), req.reason());
        return idempotent.execute(CMD_WITHDRAW, req.commandKey(), fingerprint, () -> {
            CertificateRepository.DeclarationRow row = certs
                    .findDeclarationForUpdate(declarationKey, req.version())
                    .orElseThrow(() -> ApiException.notFound(
                            "声明版本不存在: " + declarationKey + "@" + req.version()));
            // 行锁后重查命令快照：并发同键请求可能在锁等待期间已由对方提交
            var logged = idempotent.loggedResponse(CMD_WITHDRAW, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!DeclarationStatus.ACTIVE.name().equals(row.status())) {
                throw ApiException.conflict("声明版本已撤回: " + declarationKey + "@" + req.version());
            }
            String now = now();
            certs.markWithdrawn(declarationKey, req.version(), actor, now);
            CertificateResponse body = toResponse(
                    certs.findDeclaration(declarationKey, req.version()).orElseThrow());
            return new StoredResponse(201, toJson(body));
        });
    }
    /**
     * 批量绑定：先校验全部绑定的最终状态（批次存在、声明 ACTIVE、物料一致、批号匹配、
     * 未重复绑定），再在同一个事务中写入；任一失败整次回滚，查询不到半成品绑定。
     */
    public StoredResponse bind(String actorId, BindCertificatesRequest req) {
        String actor = requireActor(actorId);
        List<BindCertificatesRequest.BindItem> items = req.bindings();
        Set<String> seen = new HashSet<>();
        for (BindCertificatesRequest.BindItem item : items) {
            if (!seen.add(item.batchKey() + "@" + item.declarationKey())) {
                throw ApiException.conflict("请求内重复绑定: " + item.batchKey()
                        + " -> " + item.declarationKey());
            }
        }
        List<String> normalized = items.stream()
                .map(i -> i.batchKey() + "@" + i.declarationKey() + "#" + i.version())
                .sorted()
                .toList();
        List<String> parts = new ArrayList<>();
        parts.add("bind");
        parts.add(actor);
        parts.addAll(normalized);
        String fingerprint = IdempotentExecutor.fingerprint(parts.toArray(new String[0]));
        return idempotent.execute(CMD_BIND, req.commandKey(), fingerprint, () -> {
            // 先按业务键排序锁定全部涉及批次（锁顺序确定，避免死锁），再重查命令快照：
            // 并发同键请求可能在锁等待期间已由对方提交，此时须重放首次结果
            java.util.Map<String, BatchRepository.BatchRow> batchesByKey = new java.util.HashMap<>();
            List<String> involvedBatches = items.stream()
                    .map(BindCertificatesRequest.BindItem::batchKey)
                    .distinct().sorted().toList();
            for (String batchKey : involvedBatches) {
                batchesByKey.put(batchKey, batches.findBatchForUpdate(batchKey)
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey)));
            }
            var logged = idempotent.loggedResponse(CMD_BIND, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            record ValidatedBind(BatchRepository.BatchRow batch,
                                 CertificateRepository.DeclarationRow declaration,
                                 String matchedPrefix) {
            }
            List<ValidatedBind> validated = new ArrayList<>(items.size());
            for (BindCertificatesRequest.BindItem item : items) {
                BatchRepository.BatchRow batch = batchesByKey.get(item.batchKey());
                CertificateRepository.DeclarationRow declaration = certs
                        .findDeclarationForUpdate(item.declarationKey(), item.version())
                        .orElseThrow(() -> ApiException.notFound("声明版本不存在: "
                                + item.declarationKey() + "@" + item.version()));
                if (!DeclarationStatus.ACTIVE.name().equals(declaration.status())) {
                    throw ApiException.unprocessable("DECLARATION_WITHDRAWN",
                            "声明版本已撤回，不可绑定: " + item.declarationKey() + "@" + item.version());
                }
                if (!declaration.materialCode().equals(batch.productCode())) {
                    throw ApiException.unprocessable("MATERIAL_MISMATCH",
                            "批次物料 " + batch.productCode() + " 与声明物料 "
                                    + declaration.materialCode() + " 不匹配: " + item.batchKey());
                }
                CertificateRepository.PrefixRow matched =
                        bestPrefixMatch(item.declarationKey(), item.version(), batch.batchNo());
                if (matched == null) {
                    throw ApiException.unprocessable("PREFIX_MISMATCH",
                            "批号 " + batch.batchNo() + " 不匹配声明 " + item.declarationKey()
                                    + "@" + item.version() + " 的任何允许前缀");
                }
                certs.findBinding(item.batchKey(), item.declarationKey()).ifPresent(b -> {
                    throw ApiException.conflict("批次已绑定该声明: " + item.batchKey()
                            + " -> " + item.declarationKey());
                });
                validated.add(new ValidatedBind(batch, declaration, matched.prefix()));
            }
            String now = now();
            List<BindingResponse> bodies = new ArrayList<>(validated.size());
            for (int i = 0; i < validated.size(); i++) {
                ValidatedBind v = validated.get(i);
                BindCertificatesRequest.BindItem item = items.get(i);
                certs.insertBinding(new CertificateRepository.BindingRow(0L, item.batchKey(),
                        item.declarationKey(), item.version(), v.declaration().materialCode(),
                        v.matchedPrefix(), actor, now));
                bodies.add(new BindingResponse(item.batchKey(), item.declarationKey(),
                        item.version(), v.declaration().materialCode(), v.matchedPrefix(),
                        actor, Instant.parse(now)));
            }
            BindCertificatesResponse body = new BindCertificatesResponse(items.size(),
                    bodies.size(), bodies);
            return new StoredResponse(201, toJson(body));
        });
    }
    /**
     * 合批：2～5 个当前可用的 RELEASED 父批合并为一个全新批次。
     * 按新批批号对全部父物料重新校验声明匹配（ACTIVE、前缀匹配、合批时点在有效区间内），
     * 任一父物料无有效声明整次 422 回滚；全部校验通过后在一个事务中提交。
     */
    public StoredResponse merge(String actorId, MergeBatchesRequest req) {
        String actor = requireActor(actorId);
        List<String> parents = req.parents();
        if (new HashSet<>(parents).size() != parents.size()) {
            throw ApiException.conflict("父批 batchKey 在请求内重复");
        }
        List<String> parts = new ArrayList<>();
        parts.add("merge");
        parts.add(actor);
        parts.add(req.batchKey());
        parts.add(req.batchNo());
        parts.add(req.productCode());
        parts.addAll(parents.stream().sorted().toList());
        String fingerprint = IdempotentExecutor.fingerprint(parts.toArray(new String[0]));
        return idempotent.execute(CMD_MERGE, req.commandKey(), fingerprint, () -> {
            batches.findBatch(req.batchKey()).ifPresent(b -> {
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            });
            List<String> sortedParents = parents.stream().sorted().toList();
            List<BatchRepository.BatchRow> parentRows = new ArrayList<>(parents.size());
            for (String parentKey : sortedParents) {
                batches.findBatchForUpdate(parentKey)
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + parentKey));
            }
            // 父批行锁后重查命令快照：并发同键请求可能在锁等待期间已由对方提交
            var logged = idempotent.loggedResponse(CMD_MERGE, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            Set<String> recalled = new HashSet<>(batches.findRecalledKeys());
            for (String parentKey : parents) {
                BatchRepository.BatchRow parent = batches.findBatch(parentKey).orElseThrow();
                if (!BatchStatus.RELEASED.name().equals(parent.status())) {
                    throw ApiException.conflict(
                            "父批状态 " + parent.status() + " 不允许合批，仅 RELEASED 可合批: "
                                    + parentKey);
                }
                String recalledAncestor = recalledAncestorOf(parentKey, recalled);
                if (recalledAncestor != null) {
                    throw ApiException.unprocessable(
                            "父批 " + parentKey + " 的祖先批次 " + recalledAncestor
                                    + " 已召回，禁止合批");
                }
                parentRows.add(parent);
            }
            String now = now();
            Instant mergeTime = Instant.parse(now);
            // 按父批顺序收集去重后的父物料，逐一重新校验声明匹配
            List<String> materials = new ArrayList<>();
            for (BatchRepository.BatchRow parent : parentRows) {
                if (!materials.contains(parent.productCode())) {
                    materials.add(parent.productCode());
                }
            }
            record MaterialBinding(String materialCode, CertificateRepository.DeclarationRow declaration,
                                   String matchedPrefix) {
            }
            List<MaterialBinding> materialBindings = new ArrayList<>(materials.size());
            for (String material : materials) {
                MaterialBinding found = null;
                int foundPriority = -1;
                for (CertificateRepository.DeclarationRow candidate : certs.findByMaterial(material)) {
                    if (!DeclarationStatus.ACTIVE.name().equals(candidate.status())) {
                        continue;
                    }
                    Instant from = Instant.parse(candidate.validFrom());
                    Instant to = Instant.parse(candidate.validTo());
                    if (mergeTime.compareTo(from) < 0 || mergeTime.compareTo(to) >= 0) {
                        continue;
                    }
                    CertificateRepository.PrefixRow matched = bestPrefixMatch(
                            candidate.declarationKey(), candidate.version(), req.batchNo());
                    if (matched == null) {
                        continue;
                    }
                    if (found == null || matched.priority() > foundPriority) {
                        found = new MaterialBinding(material, candidate, matched.prefix());
                        foundPriority = matched.priority();
                    }
                }
                if (found == null) {
                    throw ApiException.unprocessable("NO_MATCHING_DECLARATION",
                            "父物料 " + material + " 在合批时点无匹配批号 " + req.batchNo()
                                    + " 的有效声明，整次合批回滚");
                }
                materialBindings.add(found);
            }
            // 全部校验通过，开始写入
            batches.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(), req.productCode(),
                    req.batchNo(), now, BatchStatus.QUARANTINED.name(), now));
            LinkedHashSet<String> requiredTests = new LinkedHashSet<>();
            for (BatchRepository.BatchRow parent : parentRows) {
                requiredTests.addAll(batches.findRequiredTests(parent.batchKey()));
            }
            List<String> requiredList = List.copyOf(requiredTests);
            for (int i = 0; i < requiredList.size(); i++) {
                batches.insertRequiredTest(req.batchKey(), requiredList.get(i), i + 1);
            }
            List<MergeBatchesResponse.MergeParent> parentViews = new ArrayList<>(parentRows.size());
            for (int i = 0; i < parentRows.size(); i++) {
                BatchRepository.BatchRow parent = parentRows.get(i);
                certs.insertMerge(new CertificateRepository.MergeRow(0L, parent.batchKey(),
                        req.batchKey(), i + 1, now));
                parentViews.add(new MergeBatchesResponse.MergeParent(parent.batchKey(),
                        parent.productCode()));
            }
            for (MaterialBinding mb : materialBindings) {
                certs.insertBinding(new CertificateRepository.BindingRow(0L, req.batchKey(),
                        mb.declaration().declarationKey(), mb.declaration().version(),
                        mb.materialCode(), mb.matchedPrefix(), actor, now));
            }
            for (BatchRepository.BatchRow parent : parentRows) {
                batches.updateStatus(parent.batchKey(), BatchStatus.MERGED.name());
            }
            MergeBatchesResponse body = new MergeBatchesResponse(req.batchKey(), req.batchNo(),
                    req.productCode(), BatchStatus.QUARANTINED, parentRows.size(), 2, parentViews,
                    materialBindings.size(), requiredList, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }
    /**
     * 声明最新版本明细；不存在返回 404。
     */
    public CertificateResponse detail(String declarationKey) {
        List<CertificateRepository.DeclarationRow> versions = certs.findVersions(declarationKey);
        if (versions.isEmpty()) {
            throw ApiException.notFound("声明不存在: " + declarationKey);
        }
        return toResponse(versions.get(versions.size() - 1));
    }

    /**
     * 声明版本历史：全部版本按 version 升序；历史不因后续撤回或新版本发布而改写。
     */
    public CertificateHistoryResponse history(String declarationKey) {
        List<CertificateRepository.DeclarationRow> versions = certs.findVersions(declarationKey);
        if (versions.isEmpty()) {
            throw ApiException.notFound("声明不存在: " + declarationKey);
        }
        return new CertificateHistoryResponse(declarationKey,
                versions.stream().map(this::toResponse).toList());
    }

    /**
     * 匹配诊断：对指定物料与批号，在给定时点（缺省为当前 UTC）逐个评估该物料的全部
     * 声明版本，按 declarationKey、version 升序稳定排序；只读，不改变状态。
     */
    public CertificateDiagnosticsResponse diagnostics(String materialCode, String batchNo,
                                                      Instant at) {
        Instant evaluatedAt = at != null ? at : Instant.now(clock);
        List<CertificateDiagnosticsResponse.CandidateView> candidates = new ArrayList<>();
        int eligible = 0;
        for (CertificateRepository.DeclarationRow row : certs.findByMaterial(materialCode)) {
            CertificateRepository.PrefixRow matched =
                    bestPrefixMatch(row.declarationKey(), row.version(), batchNo);
            String reason;
            if (!DeclarationStatus.ACTIVE.name().equals(row.status())) {
                reason = "WITHDRAWN";
            } else if (matched == null) {
                reason = "PREFIX_MISMATCH";
            } else if (evaluatedAt.compareTo(Instant.parse(row.validFrom())) < 0) {
                reason = "NOT_YET_VALID";
            } else if (evaluatedAt.compareTo(Instant.parse(row.validTo())) >= 0) {
                reason = "EXPIRED";
            } else {
                reason = null;
            }
            if (reason == null) {
                eligible++;
            }
            candidates.add(new CertificateDiagnosticsResponse.CandidateView(row.declarationKey(),
                    row.version(), DeclarationStatus.valueOf(row.status()), reason == null, reason,
                    matched != null ? matched.prefix() : null,
                    Instant.parse(row.validFrom()), Instant.parse(row.validTo())));
        }
        return new CertificateDiagnosticsResponse(materialCode, batchNo, evaluatedAt,
                candidates.size(), eligible, candidates);
    }

    /**
     * 批次合格证明细：全部绑定（按绑定提交顺序）与放行快照（未放行或无声明约束时为 null）。
     */
    public BatchCertificatesResponse batchCertificates(String batchKey) {
        batches.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return new BatchCertificatesResponse(batchKey, bindingsOf(batchKey),
                releaseSnapshotOf(batchKey));
    }

    /**
     * 批次绑定视图列表，按绑定提交顺序稳定排序。
     */
    public List<BindingResponse> bindingsOf(String batchKey) {
        return certs.findBindings(batchKey).stream()
                .map(b -> new BindingResponse(b.batchKey(), b.declarationKey(), b.version(),
                        b.materialCode(), b.matchedPrefix(), b.boundBy(),
                        Instant.parse(b.boundAt())))
                .toList();
    }

    /**
     * 批次放行快照视图；无快照时返回 null。
     */
    public ReleaseSnapshotResponse releaseSnapshotOf(String batchKey) {
        return certs.findReleaseSnapshot(batchKey)
                .map(s -> new ReleaseSnapshotResponse(s.batchKey(), s.declarationKey(), s.version(),
                        s.matchedPrefix(), Instant.parse(s.releasedAt())))
                .orElse(null);
    }

    /**
     * 合批父批业务键，按合批请求顺序；非合批产生时为空列表。
     */
    public List<String> mergeParentsOf(String batchKey) {
        return certs.findMergeParents(batchKey);
    }
    /**
     * 放行门禁（在批准事务内调用，批次行已锁定）：
     * 物料已发布过声明（含已撤回）时，批次必须持有放行时有效的绑定，否则 422；
     * 有绑定但全部失效（撤回、过期、前缀不再匹配）时 422；
     * 否则返回放行时固化的声明版本与匹配前缀。
     * 物料从未发布声明且批次无绑定时返回 null（无声明约束，沿用原放行流程）。
     */
    public ReleaseChoice resolveReleaseBinding(BatchRepository.BatchRow batch, String now) {
        List<CertificateRepository.BindingRow> bindings = certs.findBindings(batch.batchKey());
        Instant releaseTime = Instant.parse(now);
        if (bindings.isEmpty()) {
            if (certs.existsForMaterial(batch.productCode())) {
                throw ApiException.unprocessable("CERTIFICATE_REQUIRED",
                        "物料 " + batch.productCode() + " 已发布声明，放行前必须绑定有效声明版本");
            }
            return null;
        }
        ReleaseChoice best = null;
        int bestPriority = -1;
        long bestBindingId = -1;
        List<CertificateRepository.BindingRow> ordered = bindings.stream()
                .sorted(Comparator.comparing(CertificateRepository.BindingRow::declarationKey)
                        .thenComparingInt(CertificateRepository.BindingRow::version))
                .toList();
        for (CertificateRepository.BindingRow binding : ordered) {
            CertificateRepository.DeclarationRow declaration = certs
                    .findDeclarationForUpdate(binding.declarationKey(), binding.version())
                    .orElseThrow(() -> ApiException.conflict("绑定声明版本不存在: "
                            + binding.declarationKey() + "@" + binding.version()));
            if (!DeclarationStatus.ACTIVE.name().equals(declaration.status())
                    || binding.matchedPrefix() == null) {
                continue;
            }
            Instant from = Instant.parse(declaration.validFrom());
            Instant to = Instant.parse(declaration.validTo());
            if (releaseTime.compareTo(from) < 0 || releaseTime.compareTo(to) >= 0) {
                continue;
            }
            int priority = certs.findPrefixes(binding.declarationKey(), binding.version())
                    .stream()
                    .filter(p -> p.prefix().equals(binding.matchedPrefix()))
                    .mapToInt(CertificateRepository.PrefixRow::priority)
                    .findFirst()
                    .orElse(0);
            if (best == null || priority > bestPriority
                    || (priority == bestPriority && binding.id() > bestBindingId)) {
                best = new ReleaseChoice(binding.declarationKey(), binding.version(),
                        binding.matchedPrefix());
                bestPriority = priority;
                bestBindingId = binding.id();
            }
        }
        if (best == null) {
            throw ApiException.unprocessable("BINDING_NOT_VALID",
                    "批次 " + batch.batchKey() + " 的绑定声明已撤回、过期或前缀不再匹配，"
                            + "放行被阻止");
        }
        return best;
    }

    /**
     * 写入放行快照（在批准事务内调用）：固化声明版本、匹配前缀与放行时点。
     */
    public void saveReleaseSnapshot(String batchKey, ReleaseChoice choice, String now) {
        certs.insertReleaseSnapshot(new CertificateRepository.ReleaseSnapshotRow(0L, batchKey,
                choice.declarationKey(), choice.version(), choice.matchedPrefix(), now));
    }

    /**
     * 拆分继承：子批继承父批的声明引用（declarationKey+version），匹配前缀按子批批号重算，
     * 无匹配时为 null（放行时视为无效绑定，需重新绑定）。
     */
    public void inheritBindings(String parentKey, String childKey, String childBatchNo,
                                String now) {
        for (CertificateRepository.BindingRow parentBinding : certs.findBindings(parentKey)) {
            CertificateRepository.PrefixRow matched = bestPrefixMatch(
                    parentBinding.declarationKey(), parentBinding.version(), childBatchNo);
            certs.insertBinding(new CertificateRepository.BindingRow(0L, childKey,
                    parentBinding.declarationKey(), parentBinding.version(),
                    parentBinding.materialCode(), matched != null ? matched.prefix() : null,
                    parentBinding.boundBy(), now));
        }
    }

    /**
     * 声明版本内按批号匹配的最高优先级前缀；findPrefixes 已按优先级降序、发布顺序升序排序，
     * 取首个匹配项。相互匹配同一批号的前缀必然重叠，发布校验已保证其优先级不同，结果唯一。
     */
    private CertificateRepository.PrefixRow bestPrefixMatch(String declarationKey, int version,
                                                            String batchNo) {
        return certs.findPrefixes(declarationKey, version).stream()
                .filter(p -> batchNo.startsWith(p.prefix()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 沿拆分父链向上查找最近的被直接召回（RECALLED）祖先；无则返回 null。
     */
    private String recalledAncestorOf(String batchKey, Set<String> recalled) {
        String current = batchKey;
        String parent = batches.findParentKey(current).orElse(null);
        while (parent != null) {
            if (recalled.contains(parent)) {
                return parent;
            }
            current = parent;
            parent = batches.findParentKey(current).orElse(null);
        }
        return null;
    }

    private CertificateResponse toResponse(CertificateRepository.DeclarationRow row) {
        List<CertificateResponse.PrefixView> prefixes = certs
                .findPrefixes(row.declarationKey(), row.version())
                .stream()
                .map(p -> new CertificateResponse.PrefixView(p.prefix(), p.priority()))
                .toList();
        return new CertificateResponse(row.declarationKey(), row.version(), row.materialCode(),
                prefixes, Instant.parse(row.validFrom()), Instant.parse(row.validTo()),
                DeclarationStatus.valueOf(row.status()), row.publishedBy(),
                Instant.parse(row.publishedAt()), row.withdrawnBy(),
                row.withdrawnAt() != null ? Instant.parse(row.withdrawnAt()) : null);
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return actorId.trim();
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}