package com.example.starter.disclosure;

import com.example.starter.disclosure.dto.CutoffEvidenceSnapshot;
import com.example.starter.disclosure.dto.CutoffReplayCode;
import com.example.starter.disclosure.dto.PackageAmendRequest;
import com.example.starter.disclosure.dto.PackageContentView;
import com.example.starter.disclosure.dto.PackageCreateRequest;
import com.example.starter.disclosure.dto.PackageItemView;
import com.example.starter.disclosure.dto.PackageRevokeRequest;
import com.example.starter.disclosure.dto.PackageSummaryView;
import com.example.starter.disclosure.dto.PackageView;
import com.example.starter.disclosure.dto.RevocationRecordView;
import com.example.starter.error.ApiException;
import com.example.starter.evidence.CommandLogRepository;
import com.example.starter.evidence.EvidenceRepository;
import com.example.starter.evidence.StoredResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 证物披露包核心服务。
 *
 * <p>所有写操作在单事务内完成：先锁案件串行锁（disclosure_case 行锁），
 * 再按规范化顺序锁定包内全部证物行，使建包/增删与借出/迁移/复核按事务提交顺序裁决。
 * 任一件证物按截至时刻重放失败即抛异常整包回滚，不生成版本、不写命令日志（失败不占键）。
 *
 * <p>成功后逐件固化链版本、位置与封签快照及生成摘要；后续迁移、复核或新增链记录均不改写包。
 */
@Service
public class DisclosureService {

    static final String OP_PACKAGE_CREATE = "DISCLOSURE_PACKAGE_CREATE";
    static final String OP_PACKAGE_AMEND = "DISCLOSURE_PACKAGE_AMEND";
    static final String OP_PACKAGE_DELIVER = "DISCLOSURE_PACKAGE_DELIVER";
    static final String OP_PACKAGE_REVOKE = "DISCLOSURE_PACKAGE_REVOKE";

    private final DisclosureCaseRepository caseRepository;
    private final DisclosurePackageRepository packageRepository;
    private final DisclosurePackageItemRepository itemRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final CutoffReplayer replayer;
    private final ObjectMapper objectMapper;

    public DisclosureService(DisclosureCaseRepository caseRepository,
                             DisclosurePackageRepository packageRepository,
                             DisclosurePackageItemRepository itemRepository,
                             EvidenceRepository evidenceRepository,
                             CommandLogRepository commandLogRepository,
                             CutoffReplayer replayer,
                             ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.packageRepository = packageRepository;
        this.itemRepository = itemRepository;
        this.evidenceRepository = evidenceRepository;
        this.commandLogRepository = commandLogRepository;
        this.replayer = replayer;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建案件披露包：整包重放校验通过才固化逐件快照与摘要。
     */
    @Transactional
    public StoredResponse createPackage(String actorId, PackageCreateRequest request,
                                        String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> evidenceKeys = normalize(request.evidenceKeys());
        LocalDateTime now = LocalDateTime.now();
        caseRepository.lock(request.caseKey(), now);
        // 持案件锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        return generate(actorId, request.caseKey(), request.commandKey(), requestHash,
                evidenceKeys, request.cutoffAt(), null, now);
    }

    /**
     * 批量增删证物：以目标包为基础计算完整最终集合，整包校验失败不改变已有包版本。
     */
    @Transactional
    public StoredResponse amendPackage(String actorId, String packageKey,
                                       PackageAmendRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DisclosurePackage base = packageRepository.findByKey(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
        LocalDateTime now = LocalDateTime.now();
        caseRepository.lock(base.caseKey(), now);
        // 持案件锁后复查幂等日志并锁定基础包行，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        base = packageRepository.findByKeyForUpdate(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
        if (base.status() == DisclosurePackageStatus.REVOKED) {
            throw ApiException.conflict("已撤销包不得增删修订: " + packageKey);
        }

        List<String> baseMembers = itemRepository.findByPackageKey(packageKey).stream()
                .map(DisclosurePackageItem::evidenceKey).sorted().toList();
        List<String> add = request.addEvidenceKeys() == null ? List.of()
                : normalize(request.addEvidenceKeys());
        List<String> remove = request.removeEvidenceKeys() == null ? List.of()
                : normalize(request.removeEvidenceKeys());
        for (String key : add) {
            if (baseMembers.contains(key)) {
                throw ApiException.badRequest("证物已在基础包中，不能重复新增: " + key);
            }
        }
        for (String key : remove) {
            if (!baseMembers.contains(key)) {
                throw ApiException.badRequest("证物不在基础包中，不能剔除: " + key);
            }
        }
        List<String> finalKeys = baseMembers.stream()
                .filter(key -> !remove.contains(key))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        add.stream().filter(key -> !finalKeys.contains(key)).forEach(finalKeys::add);
        List<String> normalized = normalize(finalKeys);
        if (normalized.isEmpty()) {
            throw ApiException.badRequest("增删后最终证物集合不能为空");
        }
        return generate(actorId, base.caseKey(), request.commandKey(), requestHash,
                normalized, request.cutoffAt(), base.packageVersion(), now);
    }

    /**
     * 交付披露包：仅 GENERATED 包可交付，已交付/已撤销返回 409。
     */
    @Transactional
    public StoredResponse deliverPackage(String actorId, String packageKey,
                                         com.example.starter.evidence.dto.CommandRequest request,
                                         String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DisclosurePackage pkg = packageRepository.findByKey(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
        LocalDateTime now = LocalDateTime.now();
        caseRepository.lock(pkg.caseKey(), now);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        pkg = packageRepository.findByKeyForUpdate(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
        if (pkg.status() != DisclosurePackageStatus.GENERATED) {
            throw ApiException.conflict("仅未交付包可交付，当前状态: " + pkg.status());
        }
        if (!packageRepository.markDelivered(packageKey, actorId, now)) {
            throw ApiException.conflict("披露包已被并发处理: " + packageKey);
        }
        DisclosurePackage updated = packageRepository.findByKey(packageKey).orElseThrow();
        return record(request.commandKey(), OP_PACKAGE_DELIVER, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 撤销披露包：仅未交付（GENERATED）包可撤销且必须写明原因；已交付不可撤销。
     */
    @Transactional
    public StoredResponse revokePackage(String actorId, String packageKey,
                                        PackageRevokeRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DisclosurePackage pkg = packageRepository.findByKey(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
        LocalDateTime now = LocalDateTime.now();
        caseRepository.lock(pkg.caseKey(), now);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        pkg = packageRepository.findByKeyForUpdate(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
        if (pkg.status() == DisclosurePackageStatus.DELIVERED) {
            throw ApiException.conflict("已交付包不可撤销，发现问题须创建更高修订包: " + packageKey);
        }
        if (pkg.status() == DisclosurePackageStatus.REVOKED) {
            throw ApiException.conflict("披露包已撤销: " + packageKey);
        }
        if (!packageRepository.markRevoked(packageKey, request.reason(), actorId, now)) {
            throw ApiException.conflict("披露包已被并发处理: " + packageKey);
        }
        DisclosurePackage updated = packageRepository.findByKey(packageKey).orElseThrow();
        return record(request.commandKey(), OP_PACKAGE_REVOKE, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 查询包内容：包视图 + 按规范化顺序排列的逐件冻结快照。
     */
    @Transactional(readOnly = true)
    public PackageContentView getPackageContent(String packageKey) {
        DisclosurePackage pkg = requirePackage(packageKey);
        return new PackageContentView(toView(pkg), loadItemViews(packageKey));
    }

    /**
     * 查询冻结摘要：建包时固化的生成摘要，之后证物任何变化都不影响该结果。
     */
    @Transactional(readOnly = true)
    public PackageSummaryView getPackageSummary(String packageKey) {
        DisclosurePackage pkg = requirePackage(packageKey);
        return new PackageSummaryView(
                pkg.packageKey(), pkg.caseKey(), pkg.packageVersion(), pkg.basedOnVersion(),
                pkg.cutoffAt().toString(), DisclosurePackageStatus.GENERATED.name(), pkg.actorId(),
                pkg.evidenceCount(), pkg.createdAt().toString(), readJson(pkg.summaryJson()));
    }

    /**
     * 查询撤销记录；包未撤销时返回 404。
     */
    @Transactional(readOnly = true)
    public RevocationRecordView getRevocation(String packageKey) {
        DisclosurePackage pkg = requirePackage(packageKey);
        if (pkg.status() != DisclosurePackageStatus.REVOKED) {
            throw ApiException.notFound("披露包无撤销记录: " + packageKey);
        }
        return new RevocationRecordView(pkg.packageKey(), pkg.caseKey(), pkg.packageVersion(),
                pkg.status(), pkg.revokedAt(), pkg.revokedBy(), pkg.revokeReason());
    }

    /**
     * 列出案件全部包（按版本顺序，含已交付与已撤销）。
     */
    @Transactional(readOnly = true)
    public List<PackageView> listByCase(String caseKey) {
        return packageRepository.findByCase(caseKey).stream().map(this::toView).toList();
    }

    /**
     * 逐件链诊断：按包自身截至时刻（或显式指定 cutoff）重放单件证物，
     * 返回通过状态或可区分失败原因码，同时附上包内冻结快照便于比对。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> diagnoseItem(String packageKey, String evidenceKey,
                                            LocalDateTime cutoffOverride) {
        DisclosurePackage pkg = requirePackage(packageKey);
        LocalDateTime cutoff = cutoffOverride != null ? cutoffOverride : pkg.cutoffAt();
        CutoffEvidenceSnapshot snapshot = replayer.replay(pkg.caseKey(), evidenceKey, cutoff);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packageKey", packageKey);
        result.put("cutoffAt", cutoff.toString());
        result.put("evidenceKey", evidenceKey);
        result.put("ok", snapshot.ok());
        result.put("reasonCode", snapshot.reasonCode() == null ? null : snapshot.reasonCode().name());
        result.put("chainVersion", snapshot.chainVersion());
        result.put("custodianAtCutoff", snapshot.custodianAtCutoff());
        result.put("sealSnapshot", snapshot.sealSnapshot() == null
                ? null : snapshot.sealSnapshot().name());
        result.put("lastInspectionPassed", snapshot.lastInspectionPassed());
        result.put("transferCount", snapshot.transferCount());
        result.put("loanCount", snapshot.loanCount());
        result.put("inspectionCount", snapshot.inspectionCount());
        itemRepository.findByPackageKey(packageKey).stream()
                .filter(item -> item.evidenceKey().equals(evidenceKey)).findFirst()
                .ifPresent(item -> result.put("frozenSnapshot", Map.of(
                        "itemOrder", item.itemOrder(),
                        "chainVersion", item.chainVersion(),
                        "custodianSnapshot", item.custodianSnapshot(),
                        "sealSnapshot", item.sealSnapshot().name())));
        return result;
    }

    /**
     * 生成一个新版本包：锁定全部证物行（规范化顺序，避免死锁），逐件重放，
     * 任一失败抛出 404/422 整包回滚；全部通过后固化包与逐件快照。
     */
    private StoredResponse generate(String actorId, String caseKey, String commandKey,
                                    String requestHash, List<String> evidenceKeys,
                                    LocalDateTime cutoff, Integer basedOnVersion,
                                    LocalDateTime now) {
        // 按规范化顺序锁定证物行，使迁移/借出/复核与建包按提交顺序裁决，且重放读取持锁一致状态。
        for (String evidenceKey : evidenceKeys) {
            evidenceRepository.findByKeyForUpdate(evidenceKey)
                    .orElseThrow(() -> replayFailure(
                            new CutoffEvidenceSnapshot(evidenceKey, false,
                                    CutoffReplayCode.NOT_EXIST, null, null, null, null,
                                    0, 0, 0, cutoff)));
        }
        List<CutoffEvidenceSnapshot> snapshots = evidenceKeys.stream()
                .map(key -> replayer.replay(caseKey, key, cutoff))
                .toList();
        snapshots.stream().filter(s -> !s.ok()).findFirst()
                .ifPresent(this::throwReplayFailure);

        int version = caseRepository.maxVersion(caseKey) + 1;
        String pkgKey = fingerprint(caseKey, evidenceKeys, cutoff, version, actorId);
        if (packageRepository.findByKey(pkgKey).isPresent()) {
            // 同键只能由同 commandKey 重放产生；走到这里说明同键被不同命令占用。
            throw ApiException.conflict("包指纹冲突，同键命令参数不一致: " + commandKey);
        }

        List<Map<String, Object>> itemSummaries = new java.util.ArrayList<>();
        int order = 0;
        for (CutoffEvidenceSnapshot snapshot : snapshots) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("evidenceKey", snapshot.evidenceKey());
            summary.put("chainVersion", snapshot.chainVersion());
            summary.put("custodianAtCutoff", snapshot.custodianAtCutoff());
            summary.put("sealSnapshot", snapshot.sealSnapshot().name());
            summary.put("lastInspectionPassed", snapshot.lastInspectionPassed());
            summary.put("transferCount", snapshot.transferCount());
            summary.put("loanCount", snapshot.loanCount());
            summary.put("inspectionCount", snapshot.inspectionCount());
            String summaryJson = writeJson(summary);
            itemRepository.insert(new DisclosurePackageItem(
                    null, pkgKey, order, snapshot.evidenceKey(), snapshot.chainVersion(),
                    snapshot.custodianAtCutoff(), SealSnapshot.INTACT,
                    snapshot.lastInspectionPassed(), snapshot.transferCount(),
                    snapshot.loanCount(), snapshot.inspectionCount(), summaryJson, now));
            itemSummaries.add(summary);
            order++;
        }

        Map<String, Object> frozen = new LinkedHashMap<>();
        frozen.put("caseKey", caseKey);
        frozen.put("cutoffAt", cutoff.toString());
        frozen.put("actorId", actorId);
        frozen.put("basedOnVersion", basedOnVersion);
        frozen.put("evidenceCount", evidenceKeys.size());
        frozen.put("items", itemSummaries);
        String summaryJson = writeJson(frozen);

        packageRepository.insert(new DisclosurePackage(
                null, pkgKey, caseKey, version, basedOnVersion, cutoff,
                DisclosurePackageStatus.GENERATED, actorId, evidenceKeys.size(),
                summaryJson, now, null, null, null, null, null));
        DisclosurePackage created = packageRepository.findByKey(pkgKey).orElseThrow();
        String operation = basedOnVersion == null ? OP_PACKAGE_CREATE : OP_PACKAGE_AMEND;
        return record(commandKey, operation, actorId, requestHash, 201, toView(created));
    }

    private ApiException replayFailure(CutoffEvidenceSnapshot snapshot) {
        if (snapshot.reasonCode() == CutoffReplayCode.NOT_EXIST) {
            return ApiException.notFound("证物不存在: " + snapshot.evidenceKey());
        }
        return ApiException.unprocessable(snapshot.reasonCode().name(),
                replayMessage(snapshot));
    }

    private void throwReplayFailure(CutoffEvidenceSnapshot snapshot) {
        throw replayFailure(snapshot);
    }

    private String replayMessage(CutoffEvidenceSnapshot snapshot) {
        String suffix = "（证物: " + snapshot.evidenceKey() + "，截至时刻: "
                + snapshot.cutoffAt() + "）";
        return switch (snapshot.reasonCode()) {
            case INTAKE_AFTER_CUTOFF -> "证物入库晚于截至时刻" + suffix;
            case FOREIGN_CASE -> "证物不属于目标案件" + suffix;
            case SEAL_BROKEN_AT_CUTOFF -> "截至时刻封签异常" + suffix;
            case PENDING_TRANSFER_AT_CUTOFF -> "截至时刻存在待核验（未决交接）状态" + suffix;
            case ACTIVE_LOAN_AT_CUTOFF -> "截至时刻借出未归还" + suffix;
            case CUSTODY_GAP_AT_CUTOFF -> "截至时刻保管链不连续" + suffix;
            case NOT_EXIST -> "证物不存在" + suffix;
        };
    }

    private DisclosurePackage requirePackage(String packageKey) {
        return packageRepository.findByKey(packageKey)
                .orElseThrow(() -> ApiException.notFound("披露包不存在: " + packageKey));
    }

    private List<PackageItemView> loadItemViews(String packageKey) {
        return itemRepository.findByPackageKey(packageKey).stream()
                .map(item -> new PackageItemView(
                        item.itemOrder(), item.evidenceKey(), item.chainVersion(),
                        item.custodianSnapshot(), item.sealSnapshot(),
                        item.lastInspectionPassed(), item.transferCount(), item.loanCount(),
                        item.inspectionCount(), readJson(item.itemSummary())))
                .toList();
    }

    /**
     * 证物键集合规范化：去重并按字典序升序排列。
     */
    private List<String> normalize(List<String> keys) {
        return keys.stream().distinct().sorted().toList();
    }

    /**
     * 包指纹：案件 + 规范化证物集合 + 截至时刻（UTC ISO）+ 包版本 + 操作者的 SHA-256。
     */
    private String fingerprint(String caseKey, List<String> evidenceKeys, LocalDateTime cutoffUtc,
                               int version, String actorId) {
        try {
            String canonical = String.join("\n",
                    caseKey,
                    String.join(",", evidenceKeys),
                    cutoffUtc.toString(),
                    Integer.toString(version),
                    actorId == null ? "" : actorId);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
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
        String json = writeJson(body);
        commandLogRepository.insert(commandKey, actorId, operation, requestHash, status, json,
                LocalDateTime.now());
        return new StoredResponse(status, json);
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("冻结摘要解析失败", e);
        }
    }

    private PackageView toView(DisclosurePackage pkg) {
        return new PackageView(
                pkg.packageKey(), pkg.caseKey(), pkg.packageVersion(), pkg.basedOnVersion(),
                pkg.cutoffAt(), pkg.status(), pkg.actorId(), pkg.evidenceCount(), pkg.createdAt(),
                pkg.deliveredAt(), pkg.deliveredBy(), pkg.revokeReason(), pkg.revokedAt(),
                pkg.revokedBy());
    }
}
