package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageItemDiagnosisView;
import com.example.starter.evidence.dto.PackageItemView;
import com.example.starter.evidence.dto.PackageItemsUpdateRequest;
import com.example.starter.evidence.dto.PackageRevocationView;
import com.example.starter.evidence.dto.PackageRevokeRequest;
import com.example.starter.evidence.dto.PackageSummaryView;
import com.example.starter.evidence.dto.PackageView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 证物披露包核心服务。建包与批量增删按截至时刻（UTC）重放每件证物的保管链、
 * 位置、封签、待核验（待接收交接）与借还状态；任一链缺口、截至时刻待核验或
 * 截至时刻借出未归还返回 422，整包不生成、整批不生效。
 * 成功包冻结链版本、位置、封签快照与生成摘要；证物侧后续迁移、复核或新增链记录不回写包。
 * 所有写操作在单事务内完成：先锁定包行/证物行，再校验并落库，最后写入幂等日志。
 */
@Service
public class DisclosurePackageService {

    static final String OP_PACKAGE_CREATE = "PACKAGE_CREATE";
    static final String OP_PACKAGE_ITEMS_UPDATE = "PACKAGE_ITEMS_UPDATE";
    static final String OP_PACKAGE_DELIVER = "PACKAGE_DELIVER";
    static final String OP_PACKAGE_REVOKE = "PACKAGE_REVOKE";

    private final DisclosurePackageRepository packageRepository;
    private final DisclosurePackageItemRepository itemRepository;
    private final EvidenceRepository evidenceRepository;
    private final TransferRecordRepository transferRepository;
    private final SealInspectionRepository inspectionRepository;
    private final LoanRecordRepository loanRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public DisclosurePackageService(DisclosurePackageRepository packageRepository,
                                    DisclosurePackageItemRepository itemRepository,
                                    EvidenceRepository evidenceRepository,
                                    TransferRecordRepository transferRepository,
                                    SealInspectionRepository inspectionRepository,
                                    LoanRecordRepository loanRepository,
                                    CommandLogRepository commandLogRepository,
                                    ObjectMapper objectMapper,
                                    EvidenceClock clock) {
        this.packageRepository = packageRepository;
        this.itemRepository = itemRepository;
        this.evidenceRepository = evidenceRepository;
        this.transferRepository = transferRepository;
        this.inspectionRepository = inspectionRepository;
        this.loanRepository = loanRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 计算建包指纹：案件 + 规范化证物集合（去重、字典序）+ 截至时刻 + 包版本 + 操作者 的 SHA-256。
     * 同时作为建包命令的幂等请求指纹：同 packageKey 同指纹重放，同键改参 409。
     */
    public String fingerprintOf(String actorId, PackageCreateRequest request) {
        List<String> normalized = normalize(request.evidenceKeys());
        String canonical = String.join("\n",
                request.caseKey(),
                String.join(",", normalized),
                request.asOf().toString(),
                String.valueOf(request.revision()),
                actorId);
        return sha256(canonical);
    }

    /**
     * 创建披露包：按截至时刻重放每件证物历史状态，全部通过后整包冻结落库。
     * 包版本必须严格大于该案件已有最高版本；任一校验失败整包不生成且不占 packageKey。
     */
    @Transactional
    public StoredResponse createPackage(String actorId, PackageCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.packageKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> normalized = normalize(request.evidenceKeys());
        // 按字典序锁定全部证物行，与借出/交接/核验串行化，按提交顺序裁决
        List<Evidence> locked = new ArrayList<>();
        for (String evidenceKey : normalized) {
            locked.add(lockEvidence(evidenceKey));
        }
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.packageKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        int maxRevision = packageRepository.maxRevisionForCase(request.caseKey());
        if (request.revision() <= maxRevision) {
            throw ApiException.conflict("包版本必须大于该案件已有最高版本: "
                    + request.revision() + " <= " + maxRevision);
        }
        List<DisclosurePackageItem> snapshots = new ArrayList<>();
        for (int i = 0; i < locked.size(); i++) {
            snapshots.add(buildSnapshot(request.packageKey(), request.caseKey(),
                    request.asOf(), locked.get(i), i + 1));
        }
        LocalDateTime now = clock.nowUtc();
        String evidenceKeysJson = toJson(normalized);
        String summary = buildSummary(request.caseKey(), request.revision(), normalized.size(),
                request.asOf());
        packageRepository.insert(request.packageKey(), request.caseKey(), request.revision(),
                request.asOf(), actorId, requestHash, evidenceKeysJson, normalized.size(),
                summary, now);
        for (DisclosurePackageItem item : snapshots) {
            itemRepository.insert(item.packageKey(), item.evidenceKey(), item.seqNo(),
                    item.custodianId(), item.sealIntact(), item.chainVersion(), item.chainDigest(),
                    item.snapshot(), now);
        }
        DisclosurePackage created = packageRepository.findByKey(request.packageKey()).orElseThrow();
        return record(request.packageKey(), OP_PACKAGE_CREATE, actorId, requestHash,
                201, toView(created));
    }

    /**
     * 批量增删证物：先校验完整最终集合（现有 − 移除 + 新增），任一失败整批不生效。
     * 仅 FROZEN 包可增删；新增证物按包截至时刻重放校验，保留证物快照不变，位次整体重排。
     */
    @Transactional
    public StoredResponse updateItems(String actorId, String packageKey,
                                      PackageItemsUpdateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DisclosurePackage pkg = lockPackage(packageKey);
        // 并发下本事务可能在包行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireFrozen(pkg, "增删证物");
        List<String> adds = normalize(request.addEvidenceKeys());
        List<String> removes = normalize(request.removeEvidenceKeys());
        if (adds.isEmpty() && removes.isEmpty()) {
            throw ApiException.badRequest("批量增删不能为空");
        }
        List<DisclosurePackageItem> current = itemRepository.findByPackageKey(packageKey);
        Map<String, DisclosurePackageItem> currentByKey = new LinkedHashMap<>();
        for (DisclosurePackageItem item : current) {
            currentByKey.put(item.evidenceKey(), item);
        }
        for (String remove : removes) {
            if (!currentByKey.containsKey(remove)) {
                throw ApiException.notFound("证物不在包内: " + remove);
            }
        }
        for (String add : adds) {
            if (currentByKey.containsKey(add)) {
                throw ApiException.conflict("证物已在包内: " + add);
            }
        }
        TreeSet<String> finalSet = new TreeSet<>(currentByKey.keySet());
        removes.forEach(finalSet::remove);
        finalSet.addAll(adds);
        if (finalSet.isEmpty()) {
            throw ApiException.unprocessable("披露包至少包含一件证物");
        }
        // 先校验完整最终集合：锁定并逐件重放新增证物，任一失败则整批不落库
        Map<String, DisclosurePackageItem> addedSnapshots = new LinkedHashMap<>();
        for (String add : adds) {
            Evidence evidence = lockEvidence(add);
            addedSnapshots.put(add, buildSnapshot(packageKey, pkg.caseKey(), pkg.asOf(),
                    evidence, 0));
        }
        LocalDateTime now = clock.nowUtc();
        for (String remove : removes) {
            itemRepository.deleteByPackageKeyAndEvidenceKey(packageKey, remove);
        }
        int seq = 1;
        for (String key : finalSet) {
            DisclosurePackageItem kept = currentByKey.get(key);
            if (kept != null) {
                itemRepository.updateSeqNo(packageKey, key, seq);
            } else {
                DisclosurePackageItem snapshot = addedSnapshots.get(key);
                itemRepository.insert(packageKey, key, seq, snapshot.custodianId(),
                        snapshot.sealIntact(), snapshot.chainVersion(), snapshot.chainDigest(),
                        snapshot.snapshot(), now);
            }
            seq++;
        }
        List<String> finalKeys = List.copyOf(finalSet);
        String summary = buildSummary(pkg.caseKey(), pkg.revision(), finalKeys.size(), pkg.asOf());
        packageRepository.updateItemsMeta(packageKey, toJson(finalKeys), finalKeys.size(),
                summary, now);
        DisclosurePackage updated = packageRepository.findByKey(packageKey).orElseThrow();
        return record(request.commandKey(), OP_PACKAGE_ITEMS_UPDATE, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 交付披露包：仅 FROZEN 包可交付；交付后不可撤销、不可增删。
     */
    @Transactional
    public StoredResponse deliver(String actorId, String packageKey,
                                  String commandKey, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DisclosurePackage pkg = lockPackage(packageKey);
        // 并发下本事务可能在包行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (pkg.status() == DisclosurePackageStatus.DELIVERED) {
            throw ApiException.conflict("披露包已交付: " + packageKey);
        }
        if (pkg.status() == DisclosurePackageStatus.REVOKED) {
            throw ApiException.conflict("已撤销包不可交付: " + packageKey);
        }
        if (!packageRepository.markDelivered(packageKey, actorId, clock.nowUtc())) {
            throw ApiException.conflict("披露包已被并发变更: " + packageKey);
        }
        DisclosurePackage delivered = packageRepository.findByKey(packageKey).orElseThrow();
        return record(commandKey, OP_PACKAGE_DELIVER, actorId, requestHash,
                200, toView(delivered));
    }

    /**
     * 撤销披露包：仅未交付（FROZEN）包可撤销，必须写原因；
     * 已交付包不可撤销，发现问题须创建更高修订包。
     */
    @Transactional
    public StoredResponse revoke(String actorId, String packageKey,
                                 PackageRevokeRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DisclosurePackage pkg = lockPackage(packageKey);
        // 并发下本事务可能在包行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (pkg.status() == DisclosurePackageStatus.DELIVERED) {
            throw ApiException.conflict("已交付包不可撤销，须创建更高修订包: " + packageKey);
        }
        if (pkg.status() == DisclosurePackageStatus.REVOKED) {
            throw ApiException.conflict("披露包已撤销: " + packageKey);
        }
        if (!packageRepository.markRevoked(packageKey, actorId, request.reason(), clock.nowUtc())) {
            throw ApiException.conflict("披露包已被并发变更: " + packageKey);
        }
        DisclosurePackage revoked = packageRepository.findByKey(packageKey).orElseThrow();
        return record(request.commandKey(), OP_PACKAGE_REVOKE, actorId, requestHash,
                200, toView(revoked));
    }

    /**
     * 查询包内容：包头 + 按规范排序的冻结明细。
     */
    @Transactional(readOnly = true)
    public PackageView getPackage(String packageKey) {
        DisclosurePackage pkg = packageRepository.findByKey(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
        return toView(pkg);
    }

    /**
     * 查询冻结摘要：包指纹、整包摘要与逐件链版本/链摘要。
     */
    @Transactional(readOnly = true)
    public PackageSummaryView summary(String packageKey) {
        DisclosurePackage pkg = packageRepository.findByKey(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
        List<PackageItemView> items = itemRepository.findByPackageKey(packageKey).stream()
                .map(this::toView)
                .toList();
        StringBuilder canonical = new StringBuilder();
        for (PackageItemView item : items) {
            canonical.append(item.evidenceKey()).append('|')
                    .append(item.chainVersion()).append('|')
                    .append(item.chainDigest()).append('\n');
        }
        return new PackageSummaryView(pkg.packageKey(), pkg.caseKey(), pkg.revision(), pkg.asOf(),
                pkg.status(), pkg.fingerprint(), sha256(canonical.toString()), pkg.itemCount(),
                pkg.summary(), pkg.createdAt(), items);
    }

    /**
     * 逐件链诊断：冻结快照与当前实时链状态对照；冻结后的迁移、复核或新增链记录
     * 只体现在当前侧，不改写包内快照。
     */
    @Transactional(readOnly = true)
    public PackageItemDiagnosisView itemDiagnosis(String packageKey, String evidenceKey) {
        DisclosurePackage pkg = packageRepository.findByKey(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
        DisclosurePackageItem item = itemRepository
                .findByPackageKeyAndEvidenceKey(packageKey, evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不在包内: " + evidenceKey));
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        Replay current = replay(evidence, null);
        boolean changed = current.chainVersion() != item.chainVersion()
                || !current.chainDigest().equals(item.chainDigest());
        return new PackageItemDiagnosisView(pkg.packageKey(), evidenceKey, item.seqNo(),
                item.custodianId(), item.sealIntact(), item.chainVersion(), item.chainDigest(),
                parseJson(item.snapshot()),
                evidence.custodianId(), evidence.status().name(),
                current.chainVersion(), current.chainDigest(), changed);
    }

    /**
     * 查询撤销记录：未撤销时 revoked=false 且原因/操作人/时刻为 NULL。
     */
    @Transactional(readOnly = true)
    public PackageRevocationView revocation(String packageKey) {
        DisclosurePackage pkg = packageRepository.findByKey(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
        boolean revoked = pkg.status() == DisclosurePackageStatus.REVOKED;
        return new PackageRevocationView(pkg.packageKey(), pkg.status(), revoked,
                pkg.revokeReason(), pkg.revokedBy(), pkg.revokedAt());
    }

    /**
     * 按截至时刻重放单件证物并生成冻结快照；任一校验失败抛出可区分原因的 422/404。
     */
    private DisclosurePackageItem buildSnapshot(String packageKey, String caseKey,
                                                LocalDateTime asOf, Evidence evidence, int seqNo) {
        String evidenceKey = evidence.evidenceKey();
        if (!evidence.caseKey().equals(caseKey)) {
            throw ApiException.unprocessable("证物不属于案件 " + caseKey + ": " + evidenceKey);
        }
        if (evidence.createdAt().isAfter(asOf)) {
            throw ApiException.unprocessable("截至时刻证物尚未入库: " + evidenceKey);
        }
        Replay replay = replay(evidence, asOf);
        if (replay.chainGap()) {
            throw ApiException.unprocessable("截至时刻保管链存在缺口: " + evidenceKey);
        }
        if (replay.sealBroken()) {
            throw ApiException.unprocessable("截至时刻封签异常: " + evidenceKey);
        }
        if (replay.transferPending()) {
            throw ApiException.unprocessable("截至时刻存在待核验（待接收交接）: " + evidenceKey);
        }
        if (replay.borrowed()) {
            throw ApiException.unprocessable("截至时刻借出未归还: " + evidenceKey);
        }
        String snapshot = toJson(snapshotBody(evidence, asOf, replay));
        return new DisclosurePackageItem(null, packageKey, evidenceKey, seqNo,
                replay.custodianId(), true, replay.chainVersion(), replay.chainDigest(),
                snapshot, null);
    }

    /**
     * 重放证物历史：asOf 为 null 表示不限截至时刻（当前完整链）。
     * 事件时间轴统一 UTC；链事件含交接发起/决定、借出、归还与封条核验。
     */
    private Replay replay(Evidence evidence, LocalDateTime asOf) {
        String evidenceKey = evidence.evidenceKey();
        List<TransferRecord> transfers = transferRepository.findByEvidenceKey(evidenceKey);
        List<LoanRecord> loans = loanRepository.findByEvidenceKey(evidenceKey);
        List<SealInspection> inspections = inspectionRepository.findByEvidenceKey(evidenceKey);

        List<ChainEvent> events = new ArrayList<>();
        for (TransferRecord transfer : transfers) {
            if (included(transfer.createdAt(), asOf)) {
                events.add(new ChainEvent("TRANSFER_INITIATE", String.valueOf(transfer.id()),
                        transfer.fromCustodian() + "->" + transfer.toCustodian(),
                        transfer.createdAt()));
            }
            if (transfer.decidedAt() != null && included(transfer.decidedAt(), asOf)) {
                events.add(new ChainEvent("TRANSFER_" + transfer.status().name(),
                        String.valueOf(transfer.id()),
                        transfer.fromCustodian() + "->" + transfer.toCustodian(),
                        transfer.decidedAt()));
            }
        }
        boolean borrowed = false;
        for (LoanRecord loan : loans) {
            if (included(loan.loanAt(), asOf)) {
                events.add(new ChainEvent("LOAN_BORROW", loan.loanKey(),
                        loan.custodianId() + "->" + loan.borrowerId(), loan.loanAt()));
                if (loan.returnedAt() == null || !included(loan.returnedAt(), asOf)) {
                    borrowed = true;
                }
            }
            if (loan.returnedAt() != null && included(loan.returnedAt(), asOf)) {
                events.add(new ChainEvent("LOAN_RETURN", loan.loanKey(),
                        "sealIntact=" + loan.sealPassed(), loan.returnedAt()));
            }
        }
        boolean sealBroken = false;
        for (SealInspection inspection : inspections) {
            if (included(inspection.createdAt(), asOf)) {
                events.add(new ChainEvent("SEAL_INSPECTION", String.valueOf(inspection.id()),
                        "passed=" + inspection.passed(), inspection.createdAt()));
                if (!inspection.passed()) {
                    sealBroken = true;
                }
            }
        }
        boolean transferPending = transfers.stream().anyMatch(transfer ->
                included(transfer.createdAt(), asOf)
                        && (transfer.decidedAt() == null || !included(transfer.decidedAt(), asOf)));

        // 保管人重放：初始保管人为首条已接受交接的发起人（无任何已接受交接时为当前保管人），
        // 逐条应用截至时刻前已接受的交接；相邻交接保管人不衔接即链缺口。
        String initialCustodian = transfers.stream()
                .filter(transfer -> transfer.status() == TransferStatus.ACCEPTED)
                .min(Comparator.comparingLong(TransferRecord::id))
                .map(TransferRecord::fromCustodian)
                .orElse(evidence.custodianId());
        String custodian = initialCustodian;
        boolean chainGap = false;
        List<TransferRecord> accepted = transfers.stream()
                .filter(transfer -> transfer.status() == TransferStatus.ACCEPTED)
                .filter(transfer -> included(transfer.decidedAt(), asOf))
                .sorted(Comparator.comparing(TransferRecord::decidedAt)
                        .thenComparingLong(TransferRecord::id))
                .toList();
        for (TransferRecord transfer : accepted) {
            if (!transfer.fromCustodian().equals(custodian)) {
                chainGap = true;
            }
            custodian = transfer.toCustodian();
        }

        List<ChainEvent> ordered = events.stream()
                .sorted(Comparator.comparing(ChainEvent::occurredAt)
                        .thenComparing(ChainEvent::type)
                        .thenComparing(ChainEvent::ref))
                .toList();
        StringBuilder canonical = new StringBuilder();
        for (ChainEvent event : ordered) {
            canonical.append(event.occurredAt()).append('|')
                    .append(event.type()).append('|')
                    .append(event.ref()).append('|')
                    .append(event.detail()).append('\n');
        }
        return new Replay(custodian, sealBroken, borrowed, transferPending, chainGap,
                ordered.size(), sha256(canonical.toString()), ordered);
    }

    private boolean included(LocalDateTime eventTime, LocalDateTime asOf) {
        return asOf == null || !eventTime.isAfter(asOf);
    }

    private Map<String, Object> snapshotBody(Evidence evidence, LocalDateTime asOf, Replay replay) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("evidenceKey", evidence.evidenceKey());
        body.put("caseKey", evidence.caseKey());
        body.put("category", evidence.category());
        body.put("sealNo", evidence.sealNo());
        body.put("asOf", asOf.toString());
        body.put("custodianId", replay.custodianId());
        body.put("status", EvidenceStatus.SEALED.name());
        body.put("sealIntact", true);
        body.put("borrowed", false);
        body.put("transferPending", false);
        body.put("chainVersion", replay.chainVersion());
        body.put("chainDigest", replay.chainDigest());
        List<Map<String, Object>> events = new ArrayList<>();
        for (ChainEvent event : replay.events()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", event.type());
            entry.put("ref", event.ref());
            entry.put("detail", event.detail());
            entry.put("occurredAt", event.occurredAt().toString());
            events.add(entry);
        }
        body.put("events", events);
        return body;
    }

    private String buildSummary(String caseKey, int revision, int itemCount, LocalDateTime asOf) {
        return "案件 " + caseKey + " 披露包 v" + revision + "：" + itemCount
                + " 件证物，截至 " + asOf + "Z";
    }

    private List<String> normalize(List<String> evidenceKeys) {
        if (evidenceKeys == null) {
            return List.of();
        }
        return List.copyOf(new TreeSet<>(evidenceKeys));
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private DisclosurePackage lockPackage(String packageKey) {
        return packageRepository.findByKeyForUpdate(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
    }

    private void requireFrozen(DisclosurePackage pkg, String operation) {
        if (pkg.status() == DisclosurePackageStatus.DELIVERED) {
            throw ApiException.conflict("已交付包不可" + operation + ": " + pkg.packageKey());
        }
        if (pkg.status() == DisclosurePackageStatus.REVOKED) {
            throw ApiException.conflict("已撤销包不可" + operation + ": " + pkg.packageKey());
        }
    }

    private Optional<StoredResponse> checkReplay(String commandKey, String requestHash) {
        return commandLogRepository.findByKey(commandKey).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("幂等键已被不同参数使用: " + commandKey);
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        });
    }

    private StoredResponse record(String commandKey, String operation, String actorId,
                                  String requestHash, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.insert(commandKey, actorId, operation, requestHash, status, json,
                clock.nowUtc());
        return new StoredResponse(status, json);
    }

    private PackageView toView(DisclosurePackage pkg) {
        List<PackageItemView> items = itemRepository.findByPackageKey(pkg.packageKey()).stream()
                .map(this::toView)
                .toList();
        return new PackageView(pkg.packageKey(), pkg.caseKey(), pkg.revision(), pkg.asOf(),
                pkg.status(), pkg.operatorId(), pkg.fingerprint(), pkg.itemCount(), pkg.summary(),
                pkg.deliveredBy(), pkg.deliveredAt(), pkg.revokedBy(), pkg.revokedAt(),
                pkg.revokeReason(), pkg.createdAt(), pkg.updatedAt(), items);
    }

    private PackageItemView toView(DisclosurePackageItem item) {
        return new PackageItemView(item.evidenceKey(), item.seqNo(), item.custodianId(),
                item.sealIntact(), item.chainVersion(), item.chainDigest());
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("快照反序列化失败", e);
        }
    }

    private static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 链事件：重放期间的一条保管链记录。
     *
     * @param type       事件类型（TRANSFER_INITIATE/TRANSFER_ACCEPTED/TRANSFER_CANCELLED/
     *                   LOAN_BORROW/LOAN_RETURN/SEAL_INSPECTION）
     * @param ref        事件引用（交接/核验记录 id 或借出业务键）
     * @param detail     人读细节（如保管人流转、核验结果）
     * @param occurredAt 事件发生时刻（UTC）
     */
    private record ChainEvent(String type, String ref, String detail, LocalDateTime occurredAt) {
    }

    /**
     * 单件证物截至时刻的重放结果。
     *
     * @param custodianId     截至时刻保管人
     * @param sealBroken      截至时刻封签是否异常
     * @param borrowed        截至时刻是否借出未归还
     * @param transferPending 截至时刻是否存在待核验（待接收交接）
     * @param chainGap        截至时刻保管链是否存在缺口
     * @param chainVersion    截至时刻链版本（已生效链事件总数）
     * @param chainDigest     截至时刻链事件摘要
     * @param events          截至时刻已生效链事件（按时刻、类型、引用排序）
     */
    private record Replay(String custodianId, boolean sealBroken, boolean borrowed,
                          boolean transferPending, boolean chainGap, int chainVersion,
                          String chainDigest, List<ChainEvent> events) {
    }
}
