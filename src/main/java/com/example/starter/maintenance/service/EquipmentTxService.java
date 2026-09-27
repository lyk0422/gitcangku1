package com.example.starter.maintenance.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.CorrectionCreateRequest;
import com.example.starter.maintenance.api.dto.CorrectionResponse;
import com.example.starter.maintenance.api.dto.CycleResponse;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.EvidencePackageResponse;
import com.example.starter.maintenance.api.dto.EvidenceSubmitRequest;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.MaterialRegisterRequest;
import com.example.starter.maintenance.api.dto.MaterialResponse;
import com.example.starter.maintenance.api.dto.MaterialRevokeRequest;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.api.dto.TemplateResponse;
import com.example.starter.maintenance.api.dto.TemplateRevisionRequest;
import com.example.starter.maintenance.domain.EvidenceCheckResult;
import com.example.starter.maintenance.domain.EvidencePackage;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceConfirmation;
import com.example.starter.maintenance.domain.MaintenanceCorrection;
import com.example.starter.maintenance.domain.MaintenanceCycle;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.MaintenanceStatus;
import com.example.starter.maintenance.domain.MaintenanceTemplateItem;
import com.example.starter.maintenance.domain.Material;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养与证据包事务业务服务。
 * 写操作流程：设备行锁（设备相关操作）→ 周期行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 所有对象最终状态校验通过后才统一提交；任一规则失败抛异常整体回滚，不留半成品。
 */
@Service
public class EquipmentTxService {

    private final EquipmentRepository repository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public EquipmentTxService(EquipmentRepository repository, IdempotencyService idempotency, Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------- 登记设备 ----------

    @Transactional
    public EquipmentResponse register(RegisterEquipmentRequest req) {
        String fingerprint = req.equipmentId() + "|" + req.maintenancePeriodMinutes() + "|"
                + req.overdueThresholdMinutes();
        return idempotency.execute(req.requestId(), "REGISTER_EQUIPMENT", fingerprint,
                EquipmentResponse.class, () -> {
                    if (repository.findEquipment(req.equipmentId()).isPresent()) {
                        throw ApiException.conflict("EQUIPMENT_EXISTS", "设备已存在：" + req.equipmentId());
                    }
                    Long threshold = req.overdueThresholdMinutes();
                    if (threshold != null && threshold <= req.maintenancePeriodMinutes()) {
                        throw ApiException.unprocessable("OVERDUE_THRESHOLD_INVALID",
                                "逾期阈值（实际 " + threshold + " 分钟）必须严格大于保养周期（要求 "
                                        + req.maintenancePeriodMinutes() + " 分钟），差额 "
                                        + (req.maintenancePeriodMinutes() - threshold) + " 分钟");
                    }
                    Instant now = clock.instant();
                    repository.insertEquipment(req.equipmentId(), req.maintenancePeriodMinutes(), threshold, now);
                    repository.insertFirstCycle(req.equipmentId(), req.maintenancePeriodMinutes());
                    return new EquipmentResponse(req.equipmentId(), req.maintenancePeriodMinutes(),
                            threshold, 1L);
                });
    }

    // ---------- 新增读数 ----------

    @Transactional
    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.readingId() + "|" + req.sampledAt()
                + "|" + req.cumulativeMinutes() + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "ADD_READING", fingerprint,
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    if (repository.findReading(equipmentId, req.readingId()).isPresent()) {
                        throw ApiException.conflict("READING_EXISTS", "读数已存在：" + req.readingId());
                    }
                    if (repository.findReadingAt(equipmentId, req.sampledAt()).isPresent()) {
                        throw ApiException.unprocessable("READING_TIME_DUPLICATE",
                                "同一设备同一采样时刻仅允许一条读数");
                    }
                    checkMonotonic(equipmentId, req.sampledAt(), req.cumulativeMinutes());
                    Instant now = clock.instant();
                    repository.insertReading(
                            new Reading(equipmentId, req.readingId(), req.sampledAt(),
                                    req.cumulativeMinutes(), 1),
                            now);
                    repository.insertRevision(equipmentId, req.readingId(), 1,
                            req.cumulativeMinutes(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, req.readingId(), req.sampledAt(),
                            req.cumulativeMinutes(), 1, false, equipment.version() + 1);
                });
    }

    // ---------- 修订读数 ----------

    @Transactional
    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + readingId + "|" + req.cumulativeMinutes()
                + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "REVISE_READING", fingerprint,
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    Reading reading = repository.findReading(equipmentId, readingId)
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "读数不存在：" + readingId));
                    if (repository.existsMaintenanceAnchoringReading(equipmentId, readingId)) {
                        throw ApiException.conflict("READING_ANCHORED",
                                "读数已作为历史保养锚点，不可修订：" + readingId);
                    }
                    checkMonotonic(equipmentId, reading.sampledAt(), req.cumulativeMinutes());
                    int newRevisionNo = reading.revisionNo() + 1;
                    Instant now = clock.instant();
                    repository.updateReadingValue(equipmentId, readingId, req.cumulativeMinutes(),
                            newRevisionNo, now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            req.cumulativeMinutes(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, readingId, reading.sampledAt(),
                            req.cumulativeMinutes(), newRevisionNo, false, equipment.version() + 1);
                });
    }

    // ---------- 模板修订 ----------

    @Transactional
    public TemplateResponse reviseTemplate(String equipmentId, TemplateRevisionRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        List<TemplateRevisionRequest.TemplateItemInput> items = req.items();
        String fingerprint = equipmentId + "|" + req.expectedVersion() + "|" + items.stream()
                .map(item -> item.itemCode() + ":" + item.itemName() + ":" + item.required())
                .collect(Collectors.joining(","));
        return idempotency.execute(req.requestId(), "REVISE_TEMPLATE", fingerprint,
                TemplateResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    List<String> errors = new ArrayList<>();
                    List<String> seenCodes = new ArrayList<>();
                    for (TemplateRevisionRequest.TemplateItemInput item : items) {
                        if (seenCodes.contains(item.itemCode())) {
                            errors.add("检查项编码重复：" + item.itemCode());
                        }
                        seenCodes.add(item.itemCode());
                    }
                    if (!errors.isEmpty()) {
                        throw ApiException.unprocessable("TEMPLATE_INVALID",
                                "模板检查项校验失败", errors);
                    }
                    Integer currentVersion = repository.findCurrentTemplateVersion(equipmentId);
                    int newVersion = currentVersion == null ? 1 : currentVersion + 1;
                    Instant now = clock.instant();
                    if (currentVersion == null) {
                        repository.insertTemplate(equipmentId, newVersion, now);
                    } else {
                        repository.bumpTemplateVersion(equipmentId, newVersion, now);
                    }
                    for (int i = 0; i < items.size(); i++) {
                        TemplateRevisionRequest.TemplateItemInput item = items.get(i);
                        repository.insertTemplateItem(equipmentId, newVersion, i + 1,
                                item.itemCode(), item.itemName(), item.required());
                    }
                    repository.incrementVersion(equipmentId);
                    return toTemplateResponse(equipmentId, newVersion);
                });
    }

    // ---------- 证据包提交 ----------

    @Transactional
    public EvidencePackageResponse submitEvidence(String equipmentId, EvidenceSubmitRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.expectedVersion() + "|" + req.submittedBy() + "|"
                + req.checks().stream()
                .map(check -> check.itemCode() + ":" + check.result() + ":" + check.note())
                .collect(Collectors.joining(",")) + "|"
                + req.materials().stream()
                .map(material -> material.materialId() + ":" + material.quantity().toPlainString())
                .collect(Collectors.joining(","));
        return idempotency.execute(req.requestId(), "SUBMIT_EVIDENCE", fingerprint,
                EvidencePackageResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    MaintenanceCycle cycle = repository.findOpenCycleForUpdate(equipmentId)
                            .orElseThrow(() -> ApiException.notFound("OPEN_CYCLE_NOT_FOUND",
                                    "设备不存在进行中的保养周期：" + equipmentId));
                    Integer templateVersion = repository.findCurrentTemplateVersion(equipmentId);
                    if (templateVersion == null) {
                        throw ApiException.unprocessable("TEMPLATE_NOT_FOUND",
                                "设备尚未建立保养模板，无法提交证据包");
                    }
                    List<MaintenanceTemplateItem> templateItems =
                            repository.listTemplateItems(equipmentId, templateVersion);
                    Map<String, MaintenanceTemplateItem> itemByCode = templateItems.stream()
                            .collect(Collectors.toMap(MaintenanceTemplateItem::itemCode, item -> item,
                                    (a, b) -> a, LinkedHashMap::new));

                    List<String> errors = new ArrayList<>();
                    // 校验检查项：编码不重复、必须属于当前模板、结果值合法。
                    Map<String, EvidenceSubmitRequest.CheckResultInput> checkByCode = new LinkedHashMap<>();
                    for (EvidenceSubmitRequest.CheckResultInput check : req.checks()) {
                        if (checkByCode.put(check.itemCode(), check) != null) {
                            errors.add("检查项结果重复：" + check.itemCode());
                            continue;
                        }
                        if (!itemByCode.containsKey(check.itemCode())) {
                            errors.add("检查项不属于当前模板（版本 " + templateVersion + "）："
                                    + check.itemCode());
                        }
                        if (!"PASS".equals(check.result()) && !"FAIL".equals(check.result())) {
                            errors.add("检查项结果格式非法（仅允许 PASS/FAIL）：" + check.itemCode()
                                    + "=" + check.result());
                        }
                    }
                    // 校验材料摘要：格式合法、批次存在且未撤销、同周期不重复（含同次重复）。
                    List<String> seenMaterialIds = new ArrayList<>();
                    for (EvidenceSubmitRequest.MaterialSummaryInput input : req.materials()) {
                        String materialId = input.materialId();
                        if (seenMaterialIds.contains(materialId)) {
                            errors.add("材料批次摘要在本次提交中重复：" + materialId);
                            continue;
                        }
                        seenMaterialIds.add(materialId);
                        if (input.quantity().stripTrailingZeros().scale() > 3) {
                            errors.add("材料批次摘要数量格式非法（小数精度不得超过千分位）：" + materialId
                                    + "=" + input.quantity().toPlainString());
                            continue;
                        }
                        Optional<Material> material = repository.findMaterialForUpdate(materialId);
                        if (material.isEmpty() || !material.get().active()) {
                            errors.add("材料批次不存在或已撤销：" + materialId);
                            continue;
                        }
                        if (repository.existsMaterialSummary(equipmentId, cycle.cycleNo(), materialId)) {
                            errors.add("材料批次摘要在同一设备周期内不可重复：" + materialId);
                        }
                    }
                    if (!errors.isEmpty()) {
                        throw ApiException.unprocessable("EVIDENCE_INVALID",
                                "证据包校验失败，整次提交已回滚", errors);
                    }

                    int newPackageVersion = cycle.currentEvidenceVersion() + 1;
                    Instant now = clock.instant();
                    EvidencePackage evidencePackage = new EvidencePackage(0L, equipmentId, cycle.cycleNo(),
                            newPackageVersion, templateVersion, req.submittedBy(), now, req.requestId());
                    repository.insertEvidencePackage(evidencePackage);
                    for (EvidenceSubmitRequest.CheckResultInput check : req.checks()) {
                        MaintenanceTemplateItem templateItem = itemByCode.get(check.itemCode());
                        repository.insertCheckResult(new EvidenceCheckResult(
                                equipmentId, cycle.cycleNo(), newPackageVersion,
                                templateItem.position(), check.itemCode(),
                                "PASS".equals(check.result()), check.note()));
                    }
                    for (EvidenceSubmitRequest.MaterialSummaryInput input : req.materials()) {
                        Material material = repository.findMaterialForUpdate(input.materialId()).orElseThrow();
                        repository.insertMaterialSummary(equipmentId, cycle.cycleNo(), newPackageVersion,
                                input.materialId(), input.quantity(), material.unit());
                    }
                    repository.updateCycleEvidence(equipmentId, cycle.cycleNo(),
                            newPackageVersion, templateVersion);
                    repository.incrementVersion(equipmentId);
                    return toPackageResponse(repository.findOpenCycle(equipmentId).orElseThrow(),
                            newPackageVersion, equipment.version() + 1);
                });
    }

    // ---------- 材料登记与撤销 ----------

    @Transactional
    public MaterialResponse registerMaterial(MaterialRegisterRequest req) {
        String fingerprint = req.materialId() + "|" + req.materialName() + "|" + req.unit();
        return idempotency.execute(req.requestId(), "REGISTER_MATERIAL", fingerprint,
                MaterialResponse.class, () -> {
                    if (repository.findMaterial(req.materialId()).isPresent()) {
                        throw ApiException.conflict("MATERIAL_EXISTS",
                                "材料批次已存在：" + req.materialId());
                    }
                    Instant now = clock.instant();
                    repository.insertMaterial(req.materialId(), req.materialName(), req.unit(), now);
                    return new MaterialResponse(req.materialId(), req.materialName(), req.unit(),
                            "ACTIVE", now, null, null);
                });
    }

    @Transactional
    public MaterialResponse revokeMaterial(String materialId, MaterialRevokeRequest req) {
        String fingerprint = materialId + "|" + req.reason();
        return idempotency.execute(req.requestId(), "REVOKE_MATERIAL", fingerprint,
                MaterialResponse.class, () -> {
                    // 撤销与证据引用共用材料行锁，保证"证据更新、材料撤销按提交顺序裁决"。
                    Material material = repository.findMaterialForUpdate(materialId)
                            .orElseThrow(() -> ApiException.notFound("MATERIAL_NOT_FOUND",
                                    "材料批次不存在：" + materialId));
                    if (!material.active()) {
                        throw ApiException.conflict("MATERIAL_ALREADY_REVOKED",
                                "材料批次已撤销，不可重复撤销：" + materialId);
                    }
                    Instant now = clock.instant();
                    repository.revokeMaterial(materialId, req.reason(), now);
                    return new MaterialResponse(materialId, material.materialName(), material.unit(),
                            "REVOKED", material.registeredAt(), now, req.reason());
                });
    }

    // ---------- 双人完工确认（原子完成保养并重置下一周期） ----------

    @Transactional
    public MaintenanceResponse completeMaintenance(String equipmentId, CompleteMaintenanceRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.readingId() + "|" + req.anchorRevisionNo() + "|"
                + req.expectedVersion() + "|" + req.expectedPackageVersion() + "|" + req.confirmedBy();
        return idempotency.execute(req.requestId(), "COMPLETE_MAINTENANCE", fingerprint,
                MaintenanceResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    MaintenanceCycle cycle = repository.findOpenCycleForUpdate(equipmentId)
                            .orElseThrow(() -> ApiException.notFound("OPEN_CYCLE_NOT_FOUND",
                                    "设备不存在进行中的保养周期：" + equipmentId));

                    // 锚点规则：读数存在、修订号一致、锚点时刻严格晚于周期起点（区间左闭右开）。
                    Reading anchor = repository.findReading(equipmentId, req.readingId())
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "锚点读数不存在：" + req.readingId()));
                    if (anchor.revisionNo() != req.anchorRevisionNo()) {
                        throw ApiException.conflict("ANCHOR_REVISION_CONFLICT",
                                "锚点修订号与读数当前修订号不一致：期望 " + req.anchorRevisionNo()
                                        + "，当前 " + anchor.revisionNo());
                    }
                    if (cycle.startAnchorSampledAt() != null
                            && !anchor.sampledAt().isAfter(cycle.startAnchorSampledAt())) {
                        throw ApiException.unprocessable("ANCHOR_TIME_NOT_LATER",
                                "保养锚点时间必须严格晚于本周期起点 " + cycle.startAnchorSampledAt()
                                        + "（左闭右开）");
                    }

                    // 版本裁决：尚无证据包时归入 422 门禁（证据未齐）；证据已存在但确认版本
                    // 不是最新（期间又提交了新包）则 409；证据固化后模板再修订则 409。
                    if (cycle.currentEvidenceVersion() > 0
                            && req.expectedPackageVersion() != cycle.currentEvidenceVersion()) {
                        throw ApiException.conflict("PACKAGE_VERSION_CONFLICT",
                                "证据包版本已变化：确认版本 " + req.expectedPackageVersion()
                                        + "，当前最新版本 " + cycle.currentEvidenceVersion());
                    }
                    Integer currentTemplateVersion = repository.findCurrentTemplateVersion(equipmentId);
                    if (cycle.currentTemplateVersion() != null
                            && !cycle.currentTemplateVersion().equals(currentTemplateVersion)) {
                        throw ApiException.conflict("TEMPLATE_VERSION_CONFLICT",
                                "证据提交后模板已修订：证据包基于版本 "
                                        + cycle.currentTemplateVersion() + "，当前模板版本 "
                                        + currentTemplateVersion + "，须基于新模板重新提交证据");
                    }

                    EvidencePackage evidencePackage = null;
                    if (cycle.currentEvidenceVersion() > 0) {
                        evidencePackage = repository.findEvidencePackage(equipmentId, cycle.cycleNo(),
                                cycle.currentEvidenceVersion()).orElse(null);
                    }

                    // 完整门禁校验：缺失/失败项、材料摘要、双人角色、逾期状态，一次列明全部原因。
                    List<String> details = new ArrayList<>();
                    List<MaintenanceTemplateItem> frozenItems = cycle.currentTemplateVersion() == null
                            ? List.of()
                            : repository.listTemplateItems(
                                    equipmentId, cycle.currentTemplateVersion());
                    List<EvidenceCheckResult> results = evidencePackage == null ? List.of()
                            : repository.listCheckResults(
                                    equipmentId, cycle.cycleNo(), cycle.currentEvidenceVersion());
                    Map<String, EvidenceCheckResult> resultByItem = results.stream()
                            .collect(Collectors.toMap(EvidenceCheckResult::itemCode, result -> result,
                                    (a, b) -> a, LinkedHashMap::new));
                    if (evidencePackage == null) {
                        details.add("证据包未齐：要求至少 1 个证据包版本（实际 0，差额 1），且模板尚未建立或未提交检查结果");
                    }
                    List<String> missingRequired = new ArrayList<>();
                    for (MaintenanceTemplateItem item : frozenItems) {
                        if (!item.required()) {
                            continue;
                        }
                        EvidenceCheckResult result = resultByItem.get(item.itemCode());
                        if (result == null || !result.pass()) {
                            missingRequired.add(item.itemCode());
                        }
                    }
                    List<String> failed = results.stream()
                            .filter(result -> !result.pass())
                            .map(EvidenceCheckResult::itemCode)
                            .toList();
                    if (!missingRequired.isEmpty()) {
                        details.add("必检项缺少 PASS 结果（要求每项恰有一个 PASS）："
                                + String.join(",", missingRequired));
                    }
                    if (!failed.isEmpty()) {
                        details.add("证据包存在 FAIL 检查项：" + String.join(",", failed));
                    }
                    int materialCount = repository.listMaterialSummaries(equipmentId, cycle.cycleNo()).size();
                    if (materialCount < 1) {
                        details.add("材料批次摘要缺失：要求至少 1 个，实际 " + materialCount + " 个，差额 "
                                + (1 - materialCount) + " 个");
                    }
                    if (evidencePackage != null
                            && req.confirmedBy().equals(evidencePackage.submittedBy())) {
                        details.add("完工确认人不得与证据提交人为同一人：" + req.confirmedBy());
                    }
                    StatusSnapshot statusSnapshot = computeStatus(equipment);
                    if ("OVERDUE".equals(statusSnapshot.status())) {
                        details.add("设备当前已 OVERDUE：实际运行 " + statusSnapshot.runMinutes()
                                + " 分钟，逾期阈值 " + equipment.overdueThresholdMinutes()
                                + " 分钟，超出差额 " + statusSnapshot.overdueByMinutes() + " 分钟");
                    }
                    if (!details.isEmpty()) {
                        throw ApiException.unprocessable("EVIDENCE_NOT_READY",
                                "保养完工门禁未通过，共 " + details.size() + " 项", details);
                    }

                    // 全部校验通过：保养记录、周期完工、下一周期、双人确认同事务原子落库。
                    Instant now = clock.instant();
                    long maintenanceId = repository.insertMaintenance(equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            cycle.cycleNo(), cycle.currentTemplateVersion(),
                            cycle.currentEvidenceVersion(), req.confirmedBy(), req.requestId(), now);
                    repository.completeCycle(equipmentId, cycle.cycleNo(), anchor.sampledAt(),
                            anchor.cumulativeMinutes(), maintenanceId, now);
                    repository.insertNextCycle(equipmentId, cycle.cycleNo() + 1, equipment.maintenancePeriodMinutes(),
                            anchor.sampledAt(), anchor.cumulativeMinutes());
                    repository.insertConfirmation(new MaintenanceConfirmation(0L, equipmentId,
                            cycle.cycleNo(), cycle.currentEvidenceVersion(),
                            cycle.currentTemplateVersion(),
                            maintenanceId, evidencePackage.submittedBy(), req.confirmedBy(),
                            req.requestId(), now));
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            cycle.cycleNo(), cycle.currentTemplateVersion(),
                            cycle.currentEvidenceVersion(), req.confirmedBy(), now,
                            equipment.version() + 1);
                });
    }

    // ---------- 更正附录 ----------

    @Transactional
    public CorrectionResponse addCorrection(long maintenanceId, CorrectionCreateRequest req) {
        String fingerprint = maintenanceId + "|" + req.content() + "|" + req.createdBy();
        return idempotency.execute(req.requestId(), "ADD_CORRECTION", fingerprint,
                CorrectionResponse.class, () -> {
                    MaintenanceRecord record = repository.findMaintenance(maintenanceId)
                            .orElseThrow(() -> ApiException.notFound("MAINTENANCE_NOT_FOUND",
                                    "保养记录不存在：" + maintenanceId));
                    Instant now = clock.instant();
                    MaintenanceCorrection correction = new MaintenanceCorrection(0L, maintenanceId,
                            record.equipmentId(), record.cycleNo(), req.content(), req.createdBy(),
                            req.requestId(), now);
                    long correctionId = repository.insertCorrection(correction);
                    return new CorrectionResponse(correctionId, maintenanceId, record.equipmentId(),
                            record.cycleNo(), req.content(), req.createdBy(), req.requestId(), now);
                });
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public StatusResponse getStatus(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        MaintenanceCycle openCycle = repository.findOpenCycle(equipmentId).orElse(null);
        int cycleNo = openCycle != null ? openCycle.cycleNo()
                : repository.listCycles(equipmentId).stream()
                .mapToInt(MaintenanceCycle::cycleNo).max().orElse(1);
        StatusSnapshot snapshot = computeStatus(equipment);
        long remaining = equipment.maintenancePeriodMinutes() - snapshot.runMinutes();
        return new StatusResponse(equipmentId, equipment.version(), cycleNo,
                equipment.maintenancePeriodMinutes(), equipment.overdueThresholdMinutes(),
                snapshot.latestSampledAt(), snapshot.runMinutes() + snapshot.anchorCumulativeMinutes(),
                snapshot.anchorSampledAt(), snapshot.anchorCumulativeMinutes(),
                snapshot.runMinutes(), remaining, snapshot.overdueByMinutes(), snapshot.status());
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listReadings(equipmentId).stream()
                .map(reading -> new ReadingResponse(equipmentId, reading.readingId(), reading.sampledAt(),
                        reading.cumulativeMinutes(), reading.revisionNo(),
                        repository.existsMaintenanceAnchoringReading(equipmentId, reading.readingId()),
                        equipment.version()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RevisionView> listRevisions(String equipmentId, String readingId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        repository.findReading(equipmentId, readingId)
                .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND", "读数不存在：" + readingId));
        return repository.listRevisions(equipmentId, readingId).stream()
                .map(row -> new RevisionView(row.revisionNo(), row.cumulativeMinutes(),
                        row.requestId(), row.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listMaintenances(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listMaintenances(equipmentId).stream()
                .map(record -> new MaintenanceResponse(record.maintenanceId(), equipmentId,
                        record.readingId(), record.anchorRevisionNo(), record.anchorSampledAt(),
                        record.anchorCumulativeMinutes(), record.cycleNo(), record.templateVersion(),
                        record.evidencePackageVersion(), record.confirmedBy(), record.completedAt(), 0L))
                .toList();
    }

    @Transactional(readOnly = true)
    public TemplateResponse getTemplate(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        Integer version = repository.findCurrentTemplateVersion(equipmentId);
        if (version == null) {
            throw ApiException.notFound("TEMPLATE_NOT_FOUND", "设备尚未建立保养模板：" + equipmentId);
        }
        return toTemplateResponse(equipmentId, version);
    }

    @Transactional(readOnly = true)
    public List<EvidencePackageResponse> listEvidence(String equipmentId, int cycleNo) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        MaintenanceCycle cycle = repository.findCycle(equipmentId, cycleNo)
                .orElseThrow(() -> ApiException.notFound("CYCLE_NOT_FOUND",
                        "保养周期不存在：" + equipmentId + "#" + cycleNo));
        return repository.listEvidencePackages(equipmentId, cycleNo).stream()
                .map(pkg -> toPackageResponse(cycle, pkg.packageVersion(), 0L))
                .toList();
    }

    @Transactional(readOnly = true)
    public EvidencePackageResponse getEvidence(String equipmentId, int cycleNo, int packageVersion) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        MaintenanceCycle cycle = repository.findCycle(equipmentId, cycleNo)
                .orElseThrow(() -> ApiException.notFound("CYCLE_NOT_FOUND",
                        "保养周期不存在：" + equipmentId + "#" + cycleNo));
        if (repository.findEvidencePackage(equipmentId, cycleNo, packageVersion).isEmpty()) {
            throw ApiException.notFound("EVIDENCE_NOT_FOUND",
                    "证据包版本不存在：" + equipmentId + "#" + cycleNo + "#v" + packageVersion);
        }
        return toPackageResponse(cycle, packageVersion, 0L);
    }

    @Transactional(readOnly = true)
    public CycleResponse getCurrentCycle(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        MaintenanceCycle cycle = repository.findOpenCycle(equipmentId)
                .orElseThrow(() -> ApiException.notFound("OPEN_CYCLE_NOT_FOUND",
                        "设备不存在进行中的保养周期：" + equipmentId));
        CycleResponse.ReadinessView readiness = buildReadiness(equipment, cycle);
        return toCycleResponse(cycle, repository.findCurrentTemplateVersion(equipmentId), readiness);
    }

    @Transactional(readOnly = true)
    public List<CycleResponse> listCycles(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        Integer latestTemplateVersion = repository.findCurrentTemplateVersion(equipmentId);
        return repository.listCycles(equipmentId).stream()
                .map(cycle -> toCycleResponse(cycle, latestTemplateVersion, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MaterialResponse> listMaterials() {
        return repository.listMaterials().stream()
                .map(material -> new MaterialResponse(material.materialId(), material.materialName(),
                        material.unit(), material.active() ? "ACTIVE" : "REVOKED",
                        material.registeredAt(), material.revokedAt(), material.revokeReason()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CorrectionResponse> listCorrections(String equipmentId, Long maintenanceId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listCorrections(equipmentId, maintenanceId).stream()
                .map(correction -> new CorrectionResponse(correction.correctionId(),
                        correction.maintenanceId(), correction.equipmentId(), correction.cycleNo(),
                        correction.content(), correction.createdBy(), correction.requestId(),
                        correction.createdAt()))
                .toList();
    }

    // ---------- 内部规则与装配 ----------

    private Equipment lockEquipment(String equipmentId) {
        return repository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
    }

    private ApiException equipmentNotFound(String equipmentId) {
        return ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在：" + equipmentId);
    }

    private void checkVersion(Equipment equipment, long expectedVersion) {
        if (equipment.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "设备版本冲突：期望 " + expectedVersion + "，当前 " + equipment.version());
        }
    }

    /** 单调性校验：新值须同时不早于前相邻读数、不晚于后相邻读数（按采样时刻排序）。 */
    private void checkMonotonic(String equipmentId, Instant sampledAt, long cumulativeMinutes) {
        Optional<Reading> prev = repository.findPrevReading(equipmentId, sampledAt);
        if (prev.isPresent() && cumulativeMinutes < prev.get().cumulativeMinutes()) {
            throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                    "累计工时小于前一条读数（" + prev.get().cumulativeMinutes() + "），违反单调不减约束");
        }
        Optional<Reading> next = repository.findNextReading(equipmentId, sampledAt);
        if (next.isPresent() && cumulativeMinutes > next.get().cumulativeMinutes()) {
            throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                    "累计工时大于后一条读数（" + next.get().cumulativeMinutes() + "），违反单调不减约束");
        }
    }

    /** 状态判定快照：本轮运行分钟 = 最新读数 - 最近保养锚点工时；OK / DUE / OVERDUE。 */
    private StatusSnapshot computeStatus(Equipment equipment) {
        Optional<Reading> latest = repository.findLatestReading(equipment.equipmentId());
        Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipment.equipmentId());
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        Instant anchorSampledAt = last.map(MaintenanceRecord::anchorSampledAt).orElse(null);
        long runMinutes = latestCumulative - anchorCumulative;
        Long threshold = equipment.overdueThresholdMinutes();
        long overdueBy = MaintenanceStatus.overdueByMinutes(runMinutes, threshold);
        String status = MaintenanceStatus.evaluate(runMinutes, equipment.maintenancePeriodMinutes(), threshold);
        return new StatusSnapshot(latest.map(Reading::sampledAt).orElse(null), anchorSampledAt,
                anchorCumulative, runMinutes, overdueBy, status);
    }

    private record StatusSnapshot(Instant latestSampledAt, Instant anchorSampledAt,
                                  long anchorCumulativeMinutes, long runMinutes,
                                  long overdueByMinutes, String status) {
    }

    /**
     * 完工门禁诊断（只读）：基于当前最新证据包及其固化模板版本计算；
     * 模板在证据提交后再修订时单独标注版本漂移（确认会因此返回 409）。
     */
    private CycleResponse.ReadinessView buildReadiness(Equipment equipment, MaintenanceCycle cycle) {
        List<String> reasons = new ArrayList<>();
        int requiredCount;
        int passedCount;
        List<String> missingRequired;
        List<String> failed;
        int materialCount = repository.listMaterialSummaries(equipment.equipmentId(), cycle.cycleNo()).size();

        if (cycle.currentEvidenceVersion() == 0) {
            Integer currentVersion = repository.findCurrentTemplateVersion(equipment.equipmentId());
            List<MaintenanceTemplateItem> currentItems = currentVersion == null ? List.of()
                    : repository.listTemplateItems(equipment.equipmentId(), currentVersion);
            requiredCount = (int) currentItems.stream().filter(MaintenanceTemplateItem::required).count();
            passedCount = 0;
            missingRequired = currentItems.stream()
                    .filter(MaintenanceTemplateItem::required)
                    .map(MaintenanceTemplateItem::itemCode)
                    .toList();
            failed = List.of();
            reasons.add("尚未提交证据包（实际版本 0，要求至少版本 1，差额 1）");
        } else {
            List<MaintenanceTemplateItem> frozenItems = repository.listTemplateItems(
                    equipment.equipmentId(), cycle.currentTemplateVersion());
            requiredCount = (int) frozenItems.stream().filter(MaintenanceTemplateItem::required).count();
            List<EvidenceCheckResult> results = repository.listCheckResults(
                    equipment.equipmentId(), cycle.cycleNo(), cycle.currentEvidenceVersion());
            Map<String, EvidenceCheckResult> resultByItem = results.stream()
                    .collect(Collectors.toMap(EvidenceCheckResult::itemCode, result -> result,
                            (a, b) -> a, LinkedHashMap::new));
            missingRequired = frozenItems.stream()
                    .filter(MaintenanceTemplateItem::required)
                    .filter(item -> {
                        EvidenceCheckResult result = resultByItem.get(item.itemCode());
                        return result == null || !result.pass();
                    })
                    .map(MaintenanceTemplateItem::itemCode)
                    .toList();
            failed = results.stream()
                    .filter(result -> !result.pass())
                    .map(EvidenceCheckResult::itemCode)
                    .toList();
            passedCount = (int) frozenItems.stream()
                    .filter(MaintenanceTemplateItem::required)
                    .filter(item -> {
                        EvidenceCheckResult result = resultByItem.get(item.itemCode());
                        return result != null && result.pass();
                    })
                    .count();
            if (!missingRequired.isEmpty()) {
                reasons.add("必检项缺少 PASS 结果：" + String.join(",", missingRequired));
            }
            if (!failed.isEmpty()) {
                reasons.add("证据包存在 FAIL 检查项：" + String.join(",", failed));
            }
            Integer currentTemplateVersion = repository.findCurrentTemplateVersion(equipment.equipmentId());
            if (!cycle.currentTemplateVersion().equals(currentTemplateVersion)) {
                reasons.add("模板已修订：证据包基于版本 " + cycle.currentTemplateVersion()
                        + "，当前模板版本 " + currentTemplateVersion + "，确认将返回 409");
            }
        }
        if (materialCount < 1) {
            reasons.add("材料批次摘要缺失：要求至少 1 个，实际 " + materialCount + " 个，差额 "
                    + (1 - materialCount) + " 个");
        }
        StatusSnapshot snapshot = computeStatus(equipment);
        if ("OVERDUE".equals(snapshot.status())) {
            reasons.add("设备当前已 OVERDUE：实际运行 " + snapshot.runMinutes() + " 分钟，逾期阈值 "
                    + equipment.overdueThresholdMinutes() + " 分钟，超出差额 "
                    + snapshot.overdueByMinutes() + " 分钟");
        }
        boolean ready = reasons.isEmpty();
        return new CycleResponse.ReadinessView(requiredCount, passedCount, missingRequired, failed,
                materialCount, snapshot.status(), "OVERDUE".equals(snapshot.status()), ready, reasons);
    }

    private TemplateResponse toTemplateResponse(String equipmentId, int version) {
        List<TemplateResponse.TemplateItemView> itemViews = repository
                .listTemplateItems(equipmentId, version).stream()
                .map(item -> new TemplateResponse.TemplateItemView(item.position(), item.itemCode(),
                        item.itemName(), item.required()))
                .toList();
        return new TemplateResponse(equipmentId, version, itemViews);
    }

    private EvidencePackageResponse toPackageResponse(MaintenanceCycle cycle, int packageVersion,
                                                      long equipmentVersion) {
        EvidencePackage pkg = repository.findEvidencePackage(
                cycle.equipmentId(), cycle.cycleNo(), packageVersion).orElseThrow();
        List<EvidencePackageResponse.CheckResultView> checkViews = repository
                .listCheckResults(cycle.equipmentId(), cycle.cycleNo(), packageVersion).stream()
                .map(result -> new EvidencePackageResponse.CheckResultView(result.position(),
                        result.itemCode(), result.pass() ? "PASS" : "FAIL", result.note()))
                .toList();
        List<EvidencePackageResponse.MaterialSummaryView> materialViews = repository
                .listMaterialSummariesForPackage(
                        cycle.equipmentId(), cycle.cycleNo(), packageVersion).stream()
                .map(summary -> new EvidencePackageResponse.MaterialSummaryView(summary.materialId(),
                        summary.quantity(), summary.unit(), summary.packageVersion()))
                .toList();
        return new EvidencePackageResponse(cycle.equipmentId(), cycle.cycleNo(), packageVersion,
                pkg.templateVersion(), pkg.submittedBy(), pkg.submittedAt(), pkg.requestId(),
                checkViews, materialViews, equipmentVersion);
    }

    private CycleResponse toCycleResponse(MaintenanceCycle cycle, Integer latestTemplateVersion,
                                          CycleResponse.ReadinessView readiness) {
        return new CycleResponse(cycle.cycleNo(), cycle.open() ? "OPEN" : "COMPLETED",
                cycle.periodMinutes(), cycle.startAnchorSampledAt(), cycle.startAnchorCumulativeMinutes(),
                cycle.currentEvidenceVersion(), cycle.currentTemplateVersion(), latestTemplateVersion,
                cycle.completedAt(), cycle.endAnchorSampledAt(), cycle.endAnchorCumulativeMinutes(),
                cycle.maintenanceId(), readiness);
    }
}
