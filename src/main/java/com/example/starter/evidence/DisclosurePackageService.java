package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.FrozenItemDigest;
import com.example.starter.evidence.dto.FrozenSummaryView;
import com.example.starter.evidence.dto.ItemDiagnosisView;
import com.example.starter.evidence.dto.ItemSummaryView;
import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageDeliverRequest;
import com.example.starter.evidence.dto.PackageFailureView;
import com.example.starter.evidence.dto.PackageItemView;
import com.example.starter.evidence.dto.PackageItemsUpdateRequest;
import com.example.starter.evidence.dto.PackageRevocationView;
import com.example.starter.evidence.dto.PackageRevokeRequest;
import com.example.starter.evidence.dto.PackageView;
import com.example.starter.evidence.dto.SealSnapshotView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 证物披露包服务。所有写操作在单事务内完成：先锁案件锁行（建包/增删再按规范序锁证物行），
 * 再按截至 UTC 时刻重放完整最终集合，任一件失败抛 {@link PackageReplayException}（422）回滚整包；
 * 成功才追加不可变版本与逐件固化条目并写幂等命令日志（失败不占键、不留半成品）。
 *
 * <p>包版本在案件内严格递增；增删证物生成新版本而不改写旧版本；交付/撤销只作用于案件最新版本，
 * 已交付为终态不可撤销。package_key 指纹含案件、规范化证物集合、截至时刻、包版本与操作者，同键重放。</p>
 */
@Service
public class DisclosurePackageService {

    static final String OP_PACKAGE_CREATE = "DISCLOSURE_PACKAGE_CREATE";
    static final String OP_PACKAGE_UPDATE_ITEMS = "DISCLOSURE_PACKAGE_UPDATE_ITEMS";
    static final String OP_PACKAGE_DELIVER = "DISCLOSURE_PACKAGE_DELIVER";
    static final String OP_PACKAGE_REVOKE = "DISCLOSURE_PACKAGE_REVOKE";

    private final CaseLockRepository caseLockRepository;
    private final DisclosurePackageRepository packageRepository;
    private final DisclosurePackageItemRepository itemRepository;
    private final EvidenceRepository evidenceRepository;
    private final CustodyReplayEngine replayEngine;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public DisclosurePackageService(CaseLockRepository caseLockRepository,
                                    DisclosurePackageRepository packageRepository,
                                    DisclosurePackageItemRepository itemRepository,
                                    EvidenceRepository evidenceRepository,
                                    CustodyReplayEngine replayEngine,
                                    CommandLogRepository commandLogRepository,
                                    ObjectMapper objectMapper) {
        this.caseLockRepository = caseLockRepository;
        this.packageRepository = packageRepository;
        this.itemRepository = itemRepository;
        this.evidenceRepository = evidenceRepository;
        this.replayEngine = replayEngine;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建案件披露包：按截至 UTC 时刻重放规范化证物集合，全部通过才生成首个版本。
     */
    @Transactional
    public StoredResponse createPackage(String actorId, String caseKey,
                                        PackageCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        caseLockRepository.lock(caseKey);
        // 持案件锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (packageRepository.findLatestByCase(caseKey).isPresent()) {
            throw ApiException.conflict("案件已存在披露包，增删证物请生成更高修订版本: " + caseKey);
        }
        List<String> evidenceKeys = normalize(request.evidenceKeys());
        int version = 1;
        return generateVersion(actorId, caseKey, request.commandKey(), requestHash,
                evidenceKeys, request.asOfUtc(), version, OP_PACKAGE_CREATE, 201);
    }

    /**
     * 批量增删证物：以完整最终集合通过重放校验后，在案件最新版本（含已交付/已撤销版本）
     * 之上生成更高修订版本，已有版本保持不变。
     */
    @Transactional
    public StoredResponse updateItems(String actorId, String caseKey,
                                      PackageItemsUpdateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        caseLockRepository.lock(caseKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DisclosurePackage latest = packageRepository.findLatestByCase(caseKey)
                .orElseThrow(() -> ApiException.notFound("案件尚无披露包，请先创建: " + caseKey));
        List<String> evidenceKeys = normalize(request.evidenceKeys());
        return generateVersion(actorId, caseKey, request.commandKey(), requestHash,
                evidenceKeys, request.asOfUtc(), latest.packageVersion() + 1,
                OP_PACKAGE_UPDATE_ITEMS, 200);
    }

    /**
     * 交付案件最新版本：仅 GENERATED 可交付；已交付/已撤销的最新版本返回 409。
     */
    @Transactional
    public StoredResponse deliver(String actorId, String caseKey,
                                  PackageDeliverRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        caseLockRepository.lock(caseKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DisclosurePackage latest = requireLatest(caseKey);
        if (latest.status() != PackageStatus.GENERATED) {
            throw ApiException.conflict("最新版本不是未交付状态，无法交付: "
                    + caseKey + "#" + latest.packageVersion() + "=" + latest.status());
        }
        LocalDateTime now = LocalDateTime.now();
        if (!packageRepository.deliver(latest.id(), request.reason(), actorId, now)) {
            throw ApiException.conflict("披露包已被并发处理: " + caseKey + "#" + latest.packageVersion());
        }
        DisclosurePackage delivered = packageRepository.findByCaseAndVersion(caseKey,
                latest.packageVersion()).orElseThrow();
        return record(request.commandKey(), OP_PACKAGE_DELIVER, actorId, requestHash,
                200, toView(delivered, loadItems(delivered)));
    }

    /**
     * 撤销案件最新版本：仅 GENERATED 可撤销且原因非空；已交付为终态返回 409。
     */
    @Transactional
    public StoredResponse revoke(String actorId, String caseKey,
                                 PackageRevokeRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        caseLockRepository.lock(caseKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DisclosurePackage latest = requireLatest(caseKey);
        if (latest.status() == PackageStatus.DELIVERED) {
            throw ApiException.conflict("已交付包不可撤销，发现问题须创建更高修订包: "
                    + caseKey + "#" + latest.packageVersion());
        }
        if (latest.status() == PackageStatus.REVOKED) {
            throw ApiException.conflict("最新版本已撤销，无法再次撤销: "
                    + caseKey + "#" + latest.packageVersion());
        }
        LocalDateTime now = LocalDateTime.now();
        if (!packageRepository.revoke(latest.id(), request.reason(), actorId, now)) {
            throw ApiException.conflict("披露包已被并发处理: " + caseKey + "#" + latest.packageVersion());
        }
        DisclosurePackage revoked = packageRepository.findByCaseAndVersion(caseKey,
                latest.packageVersion()).orElseThrow();
        return record(request.commandKey(), OP_PACKAGE_REVOKE, actorId, requestHash,
                200, toView(revoked, loadItems(revoked)));
    }

    /**
     * 查询案件指定版本包内容（含冻结摘要、逐件固化内容与交付/撤销记录）。
     */
    @Transactional(readOnly = true)
    public PackageView getPackage(String caseKey, int packageVersion) {
        DisclosurePackage pkg = packageRepository.findByCaseAndVersion(caseKey, packageVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "披露包版本不存在: " + caseKey + "#" + packageVersion));
        return toView(pkg, loadItems(pkg));
    }

    /**
     * 查询案件最新版本包内容。
     */
    @Transactional(readOnly = true)
    public PackageView getLatestPackage(String caseKey) {
        DisclosurePackage pkg = requireLatest(caseKey);
        return toView(pkg, loadItems(pkg));
    }

    /**
     * 查询案件全部版本（版本号升序）。
     */
    @Transactional(readOnly = true)
    public List<PackageView> listPackages(String caseKey) {
        return packageRepository.findAllByCase(caseKey).stream()
                .map(pkg -> toView(pkg, loadItems(pkg)))
                .toList();
    }

    /**
     * 查询指定版本中单件证物的逐件链诊断与固化快照。
     */
    @Transactional(readOnly = true)
    public PackageItemView getItemDiagnosis(String caseKey, int packageVersion, String evidenceKey) {
        DisclosurePackage pkg = packageRepository.findByCaseAndVersion(caseKey, packageVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "披露包版本不存在: " + caseKey + "#" + packageVersion));
        return loadItems(pkg).stream()
                .filter(item -> item.evidenceKey().equals(evidenceKey))
                .findFirst()
                .map(this::toItemView)
                .orElseThrow(() -> ApiException.notFound(
                        "该版本不包含证物: " + evidenceKey + " @ " + caseKey + "#" + packageVersion));
    }

    /**
     * 查询指定版本的撤销记录；未撤销或版本不存在时返回 404。
     */
    @Transactional(readOnly = true)
    public PackageRevocationView getRevocation(String caseKey, int packageVersion) {
        DisclosurePackage pkg = packageRepository.findByCaseAndVersion(caseKey, packageVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "披露包版本不存在: " + caseKey + "#" + packageVersion));
        if (pkg.status() != PackageStatus.REVOKED) {
            throw ApiException.notFound("该版本未撤销: " + caseKey + "#" + packageVersion);
        }
        return new PackageRevocationView(caseKey, packageVersion, pkg.revokedBy(),
                pkg.revokeReason(), pkg.revokedAt());
    }

    /**
     * 在案件锁与证物锁保护下重放完整最终集合并固化新版本。任一件失败抛 422，整事务回滚。
     */
    private StoredResponse generateVersion(String actorId, String caseKey, String commandKey,
                                           String requestHash, List<String> evidenceKeys,
                                           LocalDateTime asOfUtc, int version, String operation,
                                           int successStatus) {
        // 按规范排序顺序逐件锁证物行（固定加锁顺序，避免与既有单证物事务形成死锁）。
        List<Evidence> evidenceRows = new ArrayList<>(evidenceKeys.size());
        for (String evidenceKey : evidenceKeys) {
            evidenceRows.add(evidenceRepository.findByKeyForUpdate(evidenceKey)
                    .orElse(null));
        }

        // 完整最终集合逐件重放；收集全部失败诊断后一次性 422，原因码可区分。
        List<ItemDiagnosisView> failures = new ArrayList<>();
        List<CustodyReplayEngine.ReplayResult> replays = new ArrayList<>(evidenceKeys.size());
        for (int i = 0; i < evidenceKeys.size(); i++) {
            String evidenceKey = evidenceKeys.get(i);
            Evidence evidence = evidenceRows.get(i);
            if (evidence == null) {
                failures.add(new ItemDiagnosisView(evidenceKey, ItemDiagnosisCode.EVIDENCE_NOT_FOUND,
                        "证物不存在: " + evidenceKey));
                continue;
            }
            if (!evidence.caseKey().equals(caseKey)) {
                failures.add(new ItemDiagnosisView(evidenceKey, ItemDiagnosisCode.NOT_IN_CASE,
                        "证物不属于案件 " + caseKey + ": " + evidenceKey + " 实际属于 " + evidence.caseKey()));
                continue;
            }
            CustodyReplayEngine.ReplayResult result = replayEngine.replay(evidence, asOfUtc);
            replays.add(result);
            if (result.code() != ItemDiagnosisCode.OK) {
                failures.add(new ItemDiagnosisView(evidenceKey, result.code(), result.message()));
            }
        }
        if (!failures.isEmpty()) {
            throw new PackageReplayException(new PackageFailureView(422, caseKey, asOfUtc,
                    "披露包校验失败，整包未生成: " + failures.size() + " 件证物未通过截至时刻重放",
                    failures, LocalDateTime.now()));
        }

        LocalDateTime now = LocalDateTime.now();
        List<FrozenItemDigest> digests = new ArrayList<>(replays.size());
        for (int i = 0; i < replays.size(); i++) {
            CustodyReplayEngine.ReplayResult result = replays.get(i);
            digests.add(new FrozenItemDigest(i, result.evidenceKey(), result.custodianAtAsOf(),
                    result.summary().chainVersion(), result.sealSnapshot().intactAtAsOf()));
        }
        FrozenSummaryView frozenSummary = new FrozenSummaryView(caseKey, version, asOfUtc, actorId,
                replays.size(), now, digests);
        String packageKey = computePackageKey(caseKey, evidenceKeys, asOfUtc, version, actorId);

        long packageId = packageRepository.insert(caseKey, version, asOfUtc, packageKey, actorId,
                toJson(frozenSummary), replays.size(), now);
        for (int i = 0; i < replays.size(); i++) {
            CustodyReplayEngine.ReplayResult result = replays.get(i);
            itemRepository.insert(packageId, caseKey, version, i, result.evidenceKey(),
                    result.summary().chainVersion(), result.custodianAtAsOf(),
                    toJson(result.sealSnapshot()), toJson(result.summary()), now);
        }

        DisclosurePackage created = packageRepository.findByCaseAndVersion(caseKey, version)
                .orElseThrow();
        return record(commandKey, operation, actorId, requestHash, successStatus,
                toView(created, loadItems(created)));
    }

    private DisclosurePackage requireLatest(String caseKey) {
        return packageRepository.findLatestByCase(caseKey)
                .orElseThrow(() -> ApiException.notFound("案件尚无披露包: " + caseKey));
    }

    private List<DisclosurePackageItem> loadItems(DisclosurePackage pkg) {
        return itemRepository.findByPackageId(pkg.id());
    }

    /**
     * 请求集合去重并按字典序规范化；请求非空由 Bean Validation 保证。
     */
    private List<String> normalize(List<String> evidenceKeys) {
        return evidenceKeys.stream().distinct().sorted().toList();
    }

    private String computePackageKey(String caseKey, List<String> evidenceKeys,
                                     LocalDateTime asOfUtc, int version, String actorId) {
        String canonical = String.join("\n",
                caseKey,
                String.join(",", evidenceKeys),
                asOfUtc.toString(),
                Integer.toString(version),
                actorId == null ? "" : actorId);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
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

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("固化快照反序列化失败", e);
        }
    }

    private PackageView toView(DisclosurePackage pkg, List<DisclosurePackageItem> items) {
        FrozenSummaryView frozenSummary = fromJson(pkg.frozenSummary(), FrozenSummaryView.class);
        List<PackageItemView> itemViews = items.stream().map(this::toItemView).toList();
        return new PackageView(
                pkg.caseKey(),
                pkg.packageVersion(),
                pkg.asOfUtc(),
                pkg.status(),
                pkg.packageKey(),
                pkg.actorId(),
                pkg.itemCount(),
                frozenSummary,
                itemViews,
                pkg.deliveredBy(),
                pkg.deliveredAt(),
                pkg.deliverReason(),
                pkg.revokedBy(),
                pkg.revokedAt(),
                pkg.revokeReason(),
                pkg.createdAt());
    }

    private PackageItemView toItemView(DisclosurePackageItem item) {
        SealSnapshotView sealSnapshot = fromJson(item.sealSnapshotJson(), SealSnapshotView.class);
        ItemSummaryView summary = fromJson(item.itemSummaryJson(), ItemSummaryView.class);
        return new PackageItemView(item.position(), item.evidenceKey(), item.chainVersion(),
                item.custodianAtAsOf(), sealSnapshot, summary, ItemDiagnosisCode.OK);
    }
}
