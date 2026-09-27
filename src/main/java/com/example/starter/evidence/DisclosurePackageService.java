package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.FrozenChainEventView;
import com.example.starter.evidence.dto.FrozenItemView;
import com.example.starter.evidence.dto.ItemChainDiagnosisView;
import com.example.starter.evidence.dto.PackageAdjustRequest;
import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageDetailView;
import com.example.starter.evidence.dto.PackageRevocationView;
import com.example.starter.evidence.dto.PackageRevokeRequest;
import com.example.starter.evidence.dto.PackageView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 证物披露包核心服务。建包/增删在单事务内完成：先抢占案件串行锁确定包版本，
 * 再按规范化顺序逐件锁定证物并按截至 UTC 时刻重放历史，任一校验失败整体回滚
 * （不生成包、不占包键、不占版本、不留半成品）；全部通过后同事务固化包与逐件快照。
 * 固化后迁移、复核、新增链记录只写原表，无法改写冻结快照。
 */
@Service
public class DisclosurePackageService {

    static final String OP_PACKAGE_CREATE = "PACKAGE_CREATE";
    static final String OP_PACKAGE_ADJUST = "PACKAGE_ADJUST";
    static final String OP_PACKAGE_DELIVER = "PACKAGE_DELIVER";
    static final String OP_PACKAGE_REVOKE = "PACKAGE_REVOKE";

    private final EvidenceRepository evidenceRepository;
    private final DisclosurePackageRepository packageRepository;
    private final DisclosurePackageItemRepository itemRepository;
    private final CommandLogRepository commandLogRepository;
    private final ChainReplayEngine replayEngine;
    private final ObjectMapper objectMapper;

    public DisclosurePackageService(EvidenceRepository evidenceRepository,
                                    DisclosurePackageRepository packageRepository,
                                    DisclosurePackageItemRepository itemRepository,
                                    CommandLogRepository commandLogRepository,
                                    ChainReplayEngine replayEngine,
                                    ObjectMapper objectMapper) {
        this.evidenceRepository = evidenceRepository;
        this.packageRepository = packageRepository;
        this.itemRepository = itemRepository;
        this.commandLogRepository = commandLogRepository;
        this.replayEngine = replayEngine;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建披露包：逐件按截至时刻重放，链缺口/截至时刻待核验/借出未归还/封签异常均 422，整包不生成。
     */
    @Transactional
    public StoredResponse createPackage(String actorId, PackageCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> evidenceKeys = normalizeKeys(request.evidenceKeys());
        if (evidenceKeys.isEmpty()) {
            throw ApiException.badRequest("披露包证物集合不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        packageRepository.ensureCaseLock(request.caseKey(), now);
        packageRepository.lockCase(request.caseKey());
        // 持案件锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        int version = packageRepository.findMaxVersion(request.caseKey()) + 1;
        FrozenBuild build = freezeSet(request.caseKey(), evidenceKeys, request.cutoffAt());
        String packageKey = packageFingerprint(request.caseKey(), evidenceKeys, request.cutoffAt(),
                version, actorId);
        String digest = packageDigest(request.caseKey(), version, request.cutoffAt(), actorId,
                packageKey, build.snapshots());

        persistPackage(packageKey, request.caseKey(), request.cutoffAt(), version, actorId,
                evidenceKeys.size(), digest, null, build, now);
        PackageDetailView view = loadDetail(packageKey);
        return record(request.commandKey(), OP_PACKAGE_CREATE, actorId, requestHash, 201, view);
    }

    /**
     * 批量增删证物：在基准包集合上先增后删得到完整最终集合并整体校验，任一失败不改变已有包版本。
     * 修订包沿用基准包截至时刻，版本为案件内新版本，基准包（无论 FROZEN/DELIVERED/REVOKED）保持不变。
     */
    @Transactional
    public StoredResponse adjustPackage(String actorId, String basePackageKey,
                                        PackageAdjustRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DisclosurePackage base = packageRepository.findByKeyForUpdate(basePackageKey)
                .orElseThrow(() -> ApiException.notFound("基准披露包不存在: " + basePackageKey));
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        Set<String> baseKeys = itemRepository.findByPackageKey(basePackageKey).stream()
                .map(DisclosurePackageItem::evidenceKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> addKeys = normalizeKeys(request.addEvidenceKeys());
        List<String> removeKeys = normalizeKeys(request.removeEvidenceKeys());

        List<String> paramFailures = new ArrayList<>();
        Set<String> addSet = new LinkedHashSet<>(addKeys);
        Set<String> removeSet = new LinkedHashSet<>(removeKeys);
        for (String key : addSet) {
            if (removeSet.contains(key)) {
                paramFailures.add("同一证物不能既新增又删除: " + key);
            }
            if (baseKeys.contains(key)) {
                paramFailures.add("证物已在基准包中，不能重复新增: " + key);
            }
        }
        for (String key : removeSet) {
            if (!baseKeys.contains(key)) {
                paramFailures.add("证物不在基准包中，不能删除: " + key);
            }
        }
        if (!paramFailures.isEmpty()) {
            throw ApiException.badRequest("披露包增删参数非法: " + String.join("; ", paramFailures));
        }

        Set<String> finalSet = new LinkedHashSet<>(baseKeys);
        finalSet.addAll(addSet);
        finalSet.removeAll(removeSet);
        if (finalSet.isEmpty()) {
            throw ApiException.badRequest("增删后披露包证物集合为空，整包不生成");
        }
        List<String> finalKeys = finalSet.stream().sorted().toList();

        // 修订同样在案件串行锁内分配新版本，与建包/借出/迁移等按提交顺序裁决。
        LocalDateTime now = LocalDateTime.now();
        packageRepository.ensureCaseLock(base.caseKey(), now);
        packageRepository.lockCase(base.caseKey());
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        int version = packageRepository.findMaxVersion(base.caseKey()) + 1;
        FrozenBuild build = freezeSet(base.caseKey(), finalKeys, base.cutoffAt());
        String packageKey = packageFingerprint(base.caseKey(), finalKeys, base.cutoffAt(),
                version, actorId);
        String digest = packageDigest(base.caseKey(), version, base.cutoffAt(), actorId,
                packageKey, build.snapshots());

        persistPackage(packageKey, base.caseKey(), base.cutoffAt(), version, actorId,
                finalKeys.size(), digest, basePackageKey, build, now);
        PackageDetailView view = loadDetail(packageKey);
        return record(request.commandKey(), OP_PACKAGE_ADJUST, actorId, requestHash, 201, view);
    }

    /**
     * 交付披露包：仅 FROZEN 可交付；已交付返回 409（同键同参仍由幂等层重放首次结果），已撤销不可交付。
     */
    @Transactional
    public StoredResponse deliverPackage(String actorId, String packageKey,
                                         String commandKey, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DisclosurePackage pkg = packageRepository.findByKeyForUpdate(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
        replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (pkg.status() == DisclosurePackageStatus.DELIVERED) {
            throw ApiException.conflict("披露包已交付，不能重复交付: " + packageKey);
        }
        if (pkg.status() == DisclosurePackageStatus.REVOKED) {
            throw ApiException.conflict("披露包已撤销，不能交付: " + packageKey);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!packageRepository.markDelivered(packageKey, actorId, now)) {
            throw ApiException.conflict("披露包状态已被并发改变，交付失败: " + packageKey);
        }
        PackageDetailView view = loadDetail(packageKey);
        return record(commandKey, OP_PACKAGE_DELIVER, actorId, requestHash, 200, view.pkg());
    }

    /**
     * 撤销披露包：仅 FROZEN 可撤销并写原因；已交付包不可撤销，发现问题须创建更高修订包。
     */
    @Transactional
    public StoredResponse revokePackage(String actorId, String packageKey,
                                        PackageRevokeRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DisclosurePackage pkg = packageRepository.findByKeyForUpdate(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (pkg.status() == DisclosurePackageStatus.DELIVERED) {
            throw ApiException.conflict("已交付包不可撤销，发现问题须创建更高修订包: " + packageKey);
        }
        if (pkg.status() == DisclosurePackageStatus.REVOKED) {
            throw ApiException.conflict("披露包已撤销，不能重复撤销: " + packageKey);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!packageRepository.markRevoked(packageKey, actorId, request.reason(), now)) {
            throw ApiException.conflict("披露包状态已被并发改变，撤销失败: " + packageKey);
        }
        PackageDetailView view = loadDetail(packageKey);
        return record(request.commandKey(), OP_PACKAGE_REVOKE, actorId, requestHash, 200, view.pkg());
    }

    @Transactional(readOnly = true)
    public PackageDetailView getPackage(String packageKey) {
        return loadDetail(packageKey);
    }

    /**
     * 逐件链诊断：基于冻结快照事件重算保管衔接与截至时刻状态标志，报告链缺口。
     */
    @Transactional(readOnly = true)
    public List<ItemChainDiagnosisView> diagnose(String packageKey) {
        PackageDetailView detail = loadDetail(packageKey);
        return detail.items().stream().map(this::diagnoseItem).toList();
    }

    @Transactional(readOnly = true)
    public List<PackageView> listByCase(String caseKey) {
        return packageRepository.findByCaseKey(caseKey).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public PackageRevocationView getRevocation(String packageKey) {
        DisclosurePackage pkg = packageRepository.findByKey(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
        if (pkg.status() != DisclosurePackageStatus.REVOKED) {
            throw ApiException.notFound("披露包不存在撤销记录: " + packageKey);
        }
        return new PackageRevocationView(pkg.packageKey(), pkg.caseKey(), pkg.revokedBy(),
                pkg.revokeReason(), pkg.revokedAt());
    }

    // ---- 建包内部步骤 ----

    /**
     * 规范化证物集合：去重并按字典序排序。
     */
    private List<String> normalizeKeys(List<String> raw) {
        return raw.stream().filter(k -> k != null && !k.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream().sorted().toList();
    }

    /**
     * 逐件锁定证物（按规范化顺序加锁防死锁）并按截至时刻重放，汇总全部校验失败原因后抛出 422。
     */
    private FrozenBuild freezeSet(String caseKey, List<String> evidenceKeys, LocalDateTime cutoff) {
        List<ChainSnapshot> snapshots = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String evidenceKey : evidenceKeys) {
            Evidence evidence = evidenceRepository.findByKeyForUpdate(evidenceKey)
                    .orElse(null);
            if (evidence == null) {
                failures.add("证物不存在: " + evidenceKey);
                continue;
            }
            if (!evidence.caseKey().equals(caseKey)) {
                failures.add("证物不属于案件 " + caseKey + ": " + evidenceKey);
                continue;
            }
            ChainSnapshot snapshot = replayEngine.replay(evidence, cutoff);
            if (!snapshot.chainContinuous()) {
                failures.addAll(snapshot.gaps().stream().map(g -> evidenceKey + " -> " + g).toList());
            }
            if (snapshot.status() == null) {
                failures.add(evidenceKey + " -> 截至时刻证物尚未入库");
            }
            if (snapshot.pendingTransfer()) {
                failures.add(evidenceKey + " -> 截至时刻处于待核验（待接收交接）状态");
            }
            if (snapshot.borrowedUnreturned()) {
                failures.add(evidenceKey + " -> 截至时刻借出未归还");
            }
            if (!snapshot.sealOk()) {
                failures.add(evidenceKey + " -> 截至时刻封签异常");
            }
            snapshots.add(snapshot);
        }
        if (!failures.isEmpty()) {
            // 全部失败原因一次性返回，便于区分；整包不生成，事务回滚不留半成品。
            throw ApiException.unprocessable("披露包校验失败: " + String.join("; ", failures));
        }
        return new FrozenBuild(snapshots);
    }

    private void persistPackage(String packageKey, String caseKey, LocalDateTime cutoff, int version,
                                String actorId, int evidenceCount, String digest, String basePackageKey,
                                FrozenBuild build, LocalDateTime now) {
        DisclosurePackage pkg = new DisclosurePackage(null, packageKey, caseKey, cutoff, version,
                DisclosurePackageStatus.FROZEN, actorId, evidenceCount, digest, basePackageKey,
                null, null, null, null, null, now, now);
        packageRepository.insert(pkg);
        int order = 0;
        for (ChainSnapshot snapshot : build.snapshots()) {
            String snapshotJson = itemRepository.writeEvents(snapshot.events());
            itemRepository.insert(new DisclosurePackageItem(null, packageKey, snapshot.evidenceKey(),
                    order++, snapshot.chainVersion(), snapshot.chainHash(), snapshot.custodianId(),
                    snapshot.status(), snapshot.sealNo(), snapshot.sealOk(),
                    snapshot.latestInspectionPassed(), snapshot.itemDigest(), snapshotJson, now));
        }
    }

    private PackageDetailView loadDetail(String packageKey) {
        DisclosurePackage pkg = packageRepository.findByKey(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
        List<FrozenItemView> items = itemRepository.findByPackageKey(packageKey).stream()
                .map(item -> new FrozenItemView(item.evidenceKey(), item.sortOrder(),
                        item.chainVersion(), item.chainHash(), item.custodianId(),
                        item.evidenceStatus(), item.sealNo(), item.sealOk(),
                        item.latestInspectionPassed(), item.itemDigest(),
                        itemRepository.readEvents(item)))
                .toList();
        return new PackageDetailView(toView(pkg), items);
    }

    /**
     * packageKey 指纹：案件 + 规范化证物集合 + 截至 UTC 时刻 + 包版本 + 操作者。
     */
    static String packageFingerprint(String caseKey, List<String> sortedEvidenceKeys,
                                     LocalDateTime cutoff, int version, String actorId) {
        String canonical = String.join("\n",
                caseKey,
                String.join(",", sortedEvidenceKeys),
                cutoff.toString(),
                String.valueOf(version),
                actorId);
        return ChainReplayEngine.sha256(canonical);
    }

    private String packageDigest(String caseKey, int version, LocalDateTime cutoff, String actorId,
                                 String packageKey, List<ChainSnapshot> snapshots) {
        StringBuilder sb = new StringBuilder();
        sb.append(caseKey).append('\n').append(version).append('\n')
                .append(cutoff).append('\n').append(actorId).append('\n').append(packageKey).append('\n');
        for (ChainSnapshot snapshot : snapshots) {
            sb.append(snapshot.evidenceKey()).append(':')
                    .append(snapshot.chainVersion()).append(':')
                    .append(snapshot.itemDigest()).append('\n');
        }
        return ChainReplayEngine.sha256(sb.toString());
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
                LocalDateTime.now());
        return new StoredResponse(status, json);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    /**
     * 基于冻结事件重算单件保管衔接与截至时刻状态标志；与固化时重放结果交叉验证。
     */
    private ItemChainDiagnosisView diagnoseItem(FrozenItemView item) {
        List<String> gaps = new ArrayList<>();
        String custodian = null;
        boolean sealOk = true;
        boolean pendingTransfer = false;
        boolean borrowedUnreturned = false;
        boolean sawIntake = false;
        for (FrozenChainEventView event : item.events()) {
            switch (event.kind()) {
                case "INTAKE" -> {
                    sawIntake = true;
                    custodian = event.actorId();
                }
                case "TRANSFER" -> {
                    boolean decided = event.transferStatus() != null
                            && !event.transferStatus().equals(TransferStatus.PENDING.name());
                    if (!decided) {
                        pendingTransfer = true;
                    } else {
                        pendingTransfer = false;
                        if (TransferStatus.ACCEPTED.name().equals(event.transferStatus())) {
                            if (custodian == null || !custodian.equals(event.fromCustodian())) {
                                gaps.add("保管链缺口: 已接受交接发起保管人 " + event.fromCustodian()
                                        + " 与在任保管人 " + custodian + " 不衔接");
                            }
                            custodian = event.toCustodian();
                        }
                    }
                }
                case "LOAN_BORROW" -> {
                    borrowedUnreturned = true;
                    if (custodian == null) {
                        custodian = event.actorId();
                    }
                }
                case "LOAN_RETURN" -> {
                    borrowedUnreturned = false;
                    if (Boolean.FALSE.equals(event.passed())) {
                        sealOk = false;
                    }
                }
                case "INSPECTION" -> {
                    if (Boolean.FALSE.equals(event.passed())) {
                        sealOk = false;
                    }
                }
                default -> throw ApiException.badRequest("未知冻结链事件类型: " + event.kind());
            }
        }
        if (!sawIntake) {
            gaps.add("冻结快照缺少入库起点: " + item.evidenceKey());
        }
        boolean continuous = gaps.isEmpty();
        return new ItemChainDiagnosisView(item.evidenceKey(), item.sortOrder(), item.chainVersion(),
                item.chainHash(), continuous, List.copyOf(gaps), custodian, item.statusAtCutoff(),
                sealOk, pendingTransfer, borrowedUnreturned);
    }

    private PackageView toView(DisclosurePackage pkg) {        return new PackageView(pkg.packageKey(), pkg.caseKey(), pkg.cutoffAt(), pkg.packageVersion(),
                pkg.status(), pkg.operatorId(), pkg.evidenceCount(), pkg.packageDigest(),
                pkg.basePackageKey(), pkg.deliveredBy(), pkg.deliveredAt(), pkg.revokedBy(),
                pkg.revokeReason(), pkg.revokedAt(), pkg.createdAt(), pkg.updatedAt());
    }

    /**
     * 冻结构建中间结果：全部校验通过后的逐件历史快照（顺序与规范化集合一致）。
     */
    private record FrozenBuild(List<ChainSnapshot> snapshots) {
    }
}
