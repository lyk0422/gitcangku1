package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AppendixView;
import com.example.starter.maintenance.api.dto.CreateAppendixRequest;
import com.example.starter.maintenance.api.dto.EvidenceResponse;
import com.example.starter.maintenance.api.dto.MaterialResponse;
import com.example.starter.maintenance.api.dto.RegisterMaterialRequest;
import com.example.starter.maintenance.api.dto.RevokeMaterialRequest;
import com.example.starter.maintenance.api.dto.SaveTemplateRequest;
import com.example.starter.maintenance.api.dto.SubmitEvidenceRequest;
import com.example.starter.maintenance.api.dto.TemplateResponse;
import com.example.starter.maintenance.domain.EvidenceAppendix;
import com.example.starter.maintenance.domain.EvidenceMaterial;
import com.example.starter.maintenance.domain.EvidencePackage;
import com.example.starter.maintenance.domain.EvidenceResult;
import com.example.starter.maintenance.domain.Material;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.MaintenanceTemplate;
import com.example.starter.maintenance.domain.TemplateItem;
import com.example.starter.maintenance.store.EquipmentRepository;
import com.example.starter.maintenance.store.EvidenceRepository;
import com.example.starter.maintenance.store.MaterialRepository;
import com.example.starter.maintenance.store.TemplateRepository;

/**
 * 保养模板、材料台账与证据包事务服务。
 * 写操作在设备行锁内执行并推进设备版本；证据按“完整最终包”先校验后落库，失败整体回滚。
 */
@Service
public class EvidenceTxService {

    private final EquipmentRepository equipmentRepository;
    private final TemplateRepository templateRepository;
    private final MaterialRepository materialRepository;
    private final EvidenceRepository evidenceRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public EvidenceTxService(EquipmentRepository equipmentRepository,
                             TemplateRepository templateRepository,
                             MaterialRepository materialRepository,
                             EvidenceRepository evidenceRepository,
                             IdempotencyService idempotency,
                             Clock clock) {
        this.equipmentRepository = equipmentRepository;
        this.templateRepository = templateRepository;
        this.materialRepository = materialRepository;
        this.evidenceRepository = evidenceRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------- 模板 ----------

    @Transactional
    public TemplateResponse saveTemplate(String equipmentId, SaveTemplateRequest req) {
        var equipment = lockEquipment(equipmentId);
        long period = equipment.maintenancePeriodMinutes();
        String fingerprint = equipmentId + "|" + req.expectedVersion() + "|"
                + req.overdueThresholdMinutes() + "|"
                + req.items().stream().map(i -> i.itemCode() + ":" + i.itemTitle()).toList();
        return idempotency.execute(req.requestId(), "SAVE_TEMPLATE", fingerprint,
                TemplateResponse.class, () -> {
                    if (equipment.version() != req.expectedVersion()) {
                        throw versionConflict(req.expectedVersion(), equipment.version());
                    }
                    if (req.overdueThresholdMinutes() <= period) {
                        throw ApiException.unprocessable("THRESHOLD_INVALID",
                                "OVERDUE 阈值须严格大于保养周期：实际阈值 " + req.overdueThresholdMinutes()
                                        + " 分钟，要求大于 " + period + " 分钟，差额 "
                                        + (period - req.overdueThresholdMinutes()) + " 分钟");
                    }
                    List<String> codes = new ArrayList<>();
                    for (SaveTemplateRequest.TemplateItemInput item : req.items()) {
                        if (codes.contains(item.itemCode())) {
                            throw ApiException.unprocessable("TEMPLATE_DUPLICATE_ITEM",
                                    "同一模板内检查项编码重复：" + item.itemCode());
                        }
                        codes.add(item.itemCode());
                    }
                    int version = templateRepository.nextVersion(equipmentId);
                    Instant now = clock.instant();
                    MaintenanceTemplate template = new MaintenanceTemplate(
                            equipmentId, version, req.overdueThresholdMinutes(), now);
                    templateRepository.insertTemplate(template, req.requestId());
                    List<TemplateResponse.TemplateItemView> itemViews = new ArrayList<>();
                    for (int ordinal = 0; ordinal < req.items().size(); ordinal++) {
                        SaveTemplateRequest.TemplateItemInput input = req.items().get(ordinal);
                        templateRepository.insertItem(new TemplateItem(
                                equipmentId, version, ordinal, input.itemCode(), input.itemTitle()));
                        itemViews.add(new TemplateResponse.TemplateItemView(
                                ordinal, input.itemCode(), input.itemTitle()));
                    }
                    equipmentRepository.incrementVersion(equipmentId);
                    return new TemplateResponse(equipmentId, version,
                            req.overdueThresholdMinutes(), List.copyOf(itemViews), now);
                });
    }

    @Transactional(readOnly = true)
    public TemplateResponse getLatestTemplate(String equipmentId) {
        requireEquipment(equipmentId);
        MaintenanceTemplate template = templateRepository.findLatestTemplate(equipmentId)
                .orElseThrow(() -> ApiException.notFound("TEMPLATE_NOT_FOUND", "设备尚无保养模板"));
        return toTemplateResponse(template);
    }

    @Transactional(readOnly = true)
    public TemplateResponse getTemplate(String equipmentId, int templateVersion) {
        requireEquipment(equipmentId);
        MaintenanceTemplate template = templateRepository.findTemplate(equipmentId, templateVersion)
                .orElseThrow(() -> ApiException.notFound("TEMPLATE_NOT_FOUND",
                        "模板版本不存在：" + templateVersion));
        return toTemplateResponse(template);
    }

    private TemplateResponse toTemplateResponse(MaintenanceTemplate template) {
        List<TemplateResponse.TemplateItemView> items = templateRepository
                .listItems(template.equipmentId(), template.templateVersion()).stream()
                .map(item -> new TemplateResponse.TemplateItemView(
                        item.ordinal(), item.itemCode(), item.itemTitle()))
                .toList();
        return new TemplateResponse(template.equipmentId(), template.templateVersion(),
                template.overdueThresholdMinutes(), items, template.createdAt());
    }

    // ---------- 材料台账 ----------

    @Transactional
    public MaterialResponse registerMaterial(RegisterMaterialRequest req) {
        if (!EvidenceRules.isValidBatchCode(req.batchCode())) {
            throw ApiException.unprocessable("MATERIAL_FORMAT_INVALID",
                    "材料批次编码格式非法：要求 MAT- 开头加 4-16 位大写字母数字，实际 " + req.batchCode());
        }
        String fingerprint = req.batchCode() + "|" + req.description();
        return idempotency.execute(req.requestId(), "REGISTER_MATERIAL", fingerprint,
                MaterialResponse.class, () -> {
                    if (materialRepository.find(req.batchCode()).isPresent()) {
                        throw ApiException.conflict("MATERIAL_EXISTS",
                                "材料批次已存在：" + req.batchCode());
                    }
                    Instant now = clock.instant();
                    materialRepository.insert(new Material(req.batchCode(), true,
                            req.description(), null, now, null), req.requestId());
                    return new MaterialResponse(req.batchCode(), Material.ACTIVE,
                            req.description(), null, now, null);
                });
    }

    @Transactional
    public MaterialResponse revokeMaterial(String batchCode, RevokeMaterialRequest req) {
        String fingerprint = batchCode + "|" + req.reason();
        return idempotency.execute(req.requestId(), "REVOKE_MATERIAL", fingerprint,
                MaterialResponse.class, () -> {
                    Material material = materialRepository.find(batchCode)
                            .orElseThrow(() -> ApiException.notFound("MATERIAL_NOT_FOUND",
                                    "材料批次不存在：" + batchCode));
                    if (!material.active()) {
                        throw ApiException.conflict("MATERIAL_ALREADY_REVOKED",
                                "材料批次已撤销，撤销不可恢复：" + batchCode);
                    }
                    Instant now = clock.instant();
                    if (materialRepository.markRevoked(batchCode, req.reason(), now) == 0) {
                        throw ApiException.conflict("MATERIAL_ALREADY_REVOKED",
                                "材料批次已被并发请求撤销：" + batchCode);
                    }
                    return new MaterialResponse(batchCode, Material.REVOKED,
                            material.description(), req.reason(), material.createdAt(), now);
                });
    }

    @Transactional(readOnly = true)
    public MaterialResponse getMaterial(String batchCode) {
        Material material = materialRepository.find(batchCode)
                .orElseThrow(() -> ApiException.notFound("MATERIAL_NOT_FOUND",
                        "材料批次不存在：" + batchCode));
        return new MaterialResponse(material.batchCode(),
                material.active() ? Material.ACTIVE : Material.REVOKED,
                material.description(), material.revokeReason(),
                material.createdAt(), material.revokedAt());
    }

    // ---------- 证据包 ----------

    @Transactional
    public EvidenceResponse submitEvidence(String equipmentId, SubmitEvidenceRequest req) {
        var equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.expectedVersion() + "|" + req.submittedBy() + "|"
                + (req.checkResults() == null ? "" : req.checkResults().stream()
                        .map(r -> r.itemCode() + ":" + r.result() + ":" + r.remark()).toList())
                + "|" + (req.materials() == null ? "" : req.materials().stream()
                        .map(m -> m.batchCode() + ":" + m.quantity() + ":" + m.unit()).toList());
        return idempotency.execute(req.requestId(), "SUBMIT_EVIDENCE", fingerprint,
                EvidenceResponse.class, () -> {
                    if (equipment.version() != req.expectedVersion()) {
                        throw versionConflict(req.expectedVersion(), equipment.version());
                    }
                    int cycleNo = equipmentRepository.countMaintenance(equipmentId) + 1;
                    return doSubmitEvidence(equipment, cycleNo, req);
                });
    }

    private EvidenceResponse doSubmitEvidence(com.example.starter.maintenance.domain.Equipment equipment,
                                              int cycleNo, SubmitEvidenceRequest req) {
        String equipmentId = equipment.equipmentId();
        EvidencePackage latest = evidenceRepository.findLatestPackage(equipmentId, cycleNo).orElse(null);
        int fixedTemplateVersion;
        if (latest != null) {
            fixedTemplateVersion = latest.templateVersion();
        } else {
            MaintenanceTemplate currentTemplate = templateRepository.findLatestTemplate(equipmentId)
                    .orElseThrow(() -> ApiException.unprocessable("TEMPLATE_NOT_FOUND",
                            "设备尚无保养模板，无法提交证据包"));
            fixedTemplateVersion = currentTemplate.templateVersion();
        }
        MaintenanceTemplate template = templateRepository.findTemplate(equipmentId, fixedTemplateVersion)
                .orElseThrow(() -> ApiException.conflict("TEMPLATE_VERSION_MISSING",
                        "周期固化的模板版本不存在：" + fixedTemplateVersion));
        List<TemplateItem> items = templateRepository.listItems(equipmentId, fixedTemplateVersion);

        List<SubmitEvidenceRequest.CheckResultInput> resultInputs =
                req.checkResults() == null ? List.of() : req.checkResults();
        List<SubmitEvidenceRequest.MaterialSummaryInput> materialInputs =
                req.materials() == null ? List.of() : req.materials();
        if (resultInputs.isEmpty() && materialInputs.isEmpty()) {
            throw ApiException.unprocessable("EVIDENCE_EMPTY_UPDATE",
                    "证据更新必须至少包含一个检查项结果或一条材料批次摘要");
        }

        // 检查项输入校验：同次重复、结果取值、归属模板
        List<String> duplicateItemCodes = EvidenceRules.findDuplicateItemCodes(resultInputs);
        if (!duplicateItemCodes.isEmpty()) {
            throw ApiException.unprocessable("EVIDENCE_DUPLICATE_ITEM",
                    "同一次更新中检查项重复出现：" + String.join(", ", duplicateItemCodes));
        }
        for (SubmitEvidenceRequest.CheckResultInput input : resultInputs) {
            if (!EvidenceRules.isValidResultToken(input.result())) {
                throw ApiException.unprocessable("EVIDENCE_INVALID_RESULT",
                        "检查项结果仅允许 PASS 或 FAIL：检查项 " + input.itemCode()
                                + "，实际 " + input.result());
            }
            boolean belongs = items.stream().anyMatch(item -> item.itemCode().equals(input.itemCode()));
            if (!belongs) {
                throw ApiException.unprocessable("EVIDENCE_ITEM_NOT_IN_TEMPLATE",
                        "检查项不属于设备当前周期固化的模板（版本 " + fixedTemplateVersion + "）："
                                + input.itemCode());
            }
        }

        // 材料摘要输入校验：格式、存在性、未撤销、周期内不重复（含同次重复）
        List<String> seenBatches = new ArrayList<>();
        for (SubmitEvidenceRequest.MaterialSummaryInput input : materialInputs) {
            if (!EvidenceRules.isValidBatchCode(input.batchCode())) {
                throw ApiException.unprocessable("MATERIAL_FORMAT_INVALID",
                        "材料批次编码格式非法：要求 MAT- 开头加 4-16 位大写字母数字，实际 "
                                + input.batchCode());
            }
            if (seenBatches.contains(input.batchCode())) {
                throw ApiException.unprocessable("MATERIAL_DUPLICATE_IN_CYCLE",
                        "同一次更新中材料批次重复：" + input.batchCode());
            }
            seenBatches.add(input.batchCode());
            Material material = materialRepository.find(input.batchCode())
                    .orElseThrow(() -> ApiException.unprocessable("MATERIAL_REFERENCE_INVALID",
                            "材料批次台账中不存在：" + input.batchCode()));
            if (!material.active()) {
                throw ApiException.unprocessable("MATERIAL_REVOKED",
                        "材料批次已撤销，不可被证据包引用：" + input.batchCode());
            }
            if (evidenceRepository.existsMaterialInCycle(equipmentId, cycleNo, input.batchCode())) {
                throw ApiException.unprocessable("MATERIAL_DUPLICATE_IN_CYCLE",
                        "材料批次摘要在同一设备周期内不可重复：周期 " + cycleNo + "，批次 "
                                + input.batchCode());
            }
        }

        // 先构造完整最终包并完成全部校验，再统一落库（任一失败整次回滚，无半成品）
        int newVersion = evidenceRepository.nextPackageVersion(equipmentId, cycleNo);
        Instant now = clock.instant();

        Map<String, EvidenceRules.FinalResult> currentByItem = new LinkedHashMap<>();
        if (latest != null) {
            for (EvidenceResult row : evidenceRepository.listResultsAsOf(
                    equipmentId, cycleNo, latest.packageVersion())) {
                currentByItem.put(row.itemCode(), new EvidenceRules.FinalResult(
                        row.itemCode(), row.ordinal(), row.pass(), row.remark(),
                        row.packageVersion(), row.submittedBy(), row.createdAt()));
            }
        }
        List<EvidenceRules.FinalResult> merged = EvidenceRules.mergeResults(
                items, currentByItem, resultInputs, newVersion, req.submittedBy(), now);

        int currentMaterialCount = latest == null ? 0
                : evidenceRepository.listMaterialsAsOf(equipmentId, cycleNo, latest.packageVersion()).size();
        EvidenceRules.Evaluation evaluation = EvidenceRules.evaluate(
                items, merged, currentMaterialCount + materialInputs.size());

        EvidencePackage evidencePackage = new EvidencePackage(equipmentId, cycleNo, newVersion,
                fixedTemplateVersion, req.submittedBy(), now);
        evidenceRepository.insertPackage(evidencePackage, req.requestId());
        for (SubmitEvidenceRequest.CheckResultInput input : resultInputs) {
            EvidenceRules.FinalResult finalResult = merged.stream()
                    .filter(result -> result.itemCode().equals(input.itemCode()))
                    .findFirst().orElseThrow();
            evidenceRepository.insertResult(new EvidenceResult(0, equipmentId, cycleNo, newVersion,
                    finalResult.ordinal(), finalResult.itemCode(), finalResult.pass(),
                    finalResult.remark(), req.submittedBy(), now), req.requestId());
        }
        for (SubmitEvidenceRequest.MaterialSummaryInput input : materialInputs) {
            evidenceRepository.insertMaterial(new EvidenceMaterial(0, equipmentId, cycleNo,
                    input.batchCode(), newVersion, input.quantity(), input.unit(),
                    req.submittedBy(), now), req.requestId());
        }
        equipmentRepository.incrementVersion(equipmentId);
        return buildResponse(equipmentId, cycleNo, evidencePackage, items,
                evaluation, now);
    }

    @Transactional(readOnly = true)
    public EvidenceResponse getCurrentEvidence(String equipmentId) {
        requireEquipment(equipmentId);
        int cycleNo = equipmentRepository.countMaintenance(equipmentId) + 1;
        return getEvidence(equipmentId, cycleNo);
    }

    @Transactional(readOnly = true)
    public EvidenceResponse getEvidence(String equipmentId, int cycleNo) {
        requireEquipment(equipmentId);
        EvidencePackage latest = evidenceRepository.findLatestPackage(equipmentId, cycleNo)
                .orElseThrow(() -> ApiException.notFound("EVIDENCE_NOT_FOUND",
                        "周期 " + cycleNo + " 尚无证据包"));
        List<TemplateItem> items = templateRepository.listItems(equipmentId, latest.templateVersion());
        return buildResponse(equipmentId, cycleNo, latest, items, null, latest.createdAt());
    }

    @Transactional(readOnly = true)
    public EvidenceResponse getEvidenceVersion(String equipmentId, int cycleNo, int packageVersion) {
        requireEquipment(equipmentId);
        EvidencePackage evidencePackage = evidenceRepository.findPackage(equipmentId, cycleNo, packageVersion)
                .orElseThrow(() -> ApiException.notFound("EVIDENCE_NOT_FOUND",
                        "证据包版本不存在：周期 " + cycleNo + "，版本 " + packageVersion));
        List<TemplateItem> items = templateRepository.listItems(equipmentId,
                evidencePackage.templateVersion());
        return buildResponse(equipmentId, cycleNo, evidencePackage, items, null,
                evidencePackage.createdAt());
    }

    private EvidenceResponse buildResponse(String equipmentId, int cycleNo, EvidencePackage evidencePackage,
                                           List<TemplateItem> items,
                                           EvidenceRules.Evaluation precomputed, Instant evaluationTime) {
        List<EvidenceResult> resultRows = evidenceRepository.listResultsAsOf(
                equipmentId, cycleNo, evidencePackage.packageVersion());
        List<EvidenceMaterial> materialRows = evidenceRepository.listMaterialsAsOf(
                equipmentId, cycleNo, evidencePackage.packageVersion());
        Map<String, EvidenceResult> resultByItem = new LinkedHashMap<>();
        for (EvidenceResult row : resultRows) {
            resultByItem.put(row.itemCode(), row);
        }
        List<EvidenceRules.FinalResult> finals = resultRows.stream()
                .map(row -> new EvidenceRules.FinalResult(row.itemCode(), row.ordinal(), row.pass(),
                        row.remark(), row.packageVersion(), row.submittedBy(), row.createdAt()))
                .toList();
        EvidenceRules.Evaluation evaluation = precomputed != null
                ? precomputed
                : EvidenceRules.evaluate(items, finals, materialRows.size());

        List<EvidenceResponse.CheckResultView> resultViews = resultRows.stream()
                .map(row -> new EvidenceResponse.CheckResultView(
                        row.ordinal(), row.itemCode(),
                        row.pass() ? EvidenceResult.PASS : EvidenceResult.FAIL,
                        row.remark(), row.packageVersion(), row.submittedBy(), row.createdAt()))
                .toList();
        List<EvidenceResponse.MaterialSummaryView> materialViews = materialRows.stream()
                .map(row -> new EvidenceResponse.MaterialSummaryView(
                        row.batchCode(), row.quantity(), row.unit(),
                        row.addedVersion(), row.submittedBy(), row.createdAt()))
                .toList();
        return new EvidenceResponse(equipmentId, cycleNo, evidencePackage.packageVersion(),
                evidencePackage.templateVersion(), evidencePackage.submittedBy(),
                evidencePackage.createdAt(), evaluation.complete(),
                evaluation.missingItems(), evaluation.failedItems(),
                resultViews, materialViews);
    }

    // ---------- 完工准入门禁（供完成保养事务在同一事务内调用） ----------

    /**
     * 完工前校验当前周期证据包：必须存在、齐备（每项恰一个 PASS 结果）、至少一条材料摘要、
     * 引用材料均未撤销；不满足时抛 422 并在消息中列出缺失/失败项。
     */
    public CompletableEvidence requireCompletable(String equipmentId, int cycleNo) {
        EvidencePackage latest = evidenceRepository.findLatestPackage(equipmentId, cycleNo)
                .orElseThrow(() -> ApiException.unprocessable("EVIDENCE_INCOMPLETE",
                        "周期 " + cycleNo + " 尚未提交证据包"));
        List<TemplateItem> items = templateRepository.listItems(equipmentId, latest.templateVersion());
        List<EvidenceResult> resultRows = evidenceRepository.listResultsAsOf(
                equipmentId, cycleNo, latest.packageVersion());
        List<EvidenceMaterial> materialRows = evidenceRepository.listMaterialsAsOf(
                equipmentId, cycleNo, latest.packageVersion());
        List<EvidenceRules.FinalResult> finals = resultRows.stream()
                .map(row -> new EvidenceRules.FinalResult(row.itemCode(), row.ordinal(), row.pass(),
                        row.remark(), row.packageVersion(), row.submittedBy(), row.createdAt()))
                .toList();
        EvidenceRules.Evaluation evaluation = EvidenceRules.evaluate(items, finals, materialRows.size());
        List<String> problems = new ArrayList<>();
        if (!evaluation.missingItems().isEmpty()) {
            problems.add("缺失检查项结果：" + String.join(", ", evaluation.missingItems()));
        }
        if (!evaluation.failedItems().isEmpty()) {
            problems.add("存在 FAIL 检查项：" + String.join(", ", evaluation.failedItems()));
        }
        if (materialRows.isEmpty()) {
            problems.add("缺少材料批次摘要（至少一个）");
        }
        // 已撤销/不存在的批次保留在不可变历史快照中；若最终包已无任何有效批次则禁止完工，
        // 提交替换用的新批次后可恢复（旧行不被覆盖）。
        List<String> revokedBatches = new ArrayList<>();
        int activeBatches = 0;
        for (EvidenceMaterial row : materialRows) {
            Material material = materialRepository.find(row.batchCode()).orElse(null);
            if (material == null || !material.active()) {
                revokedBatches.add(row.batchCode());
            } else {
                activeBatches++;
            }
        }
        if (!materialRows.isEmpty() && activeBatches == 0) {
            problems.add("引用的材料批次均已撤销或不存在：" + String.join(", ", revokedBatches));
        }
        if (!problems.isEmpty()) {
            throw ApiException.unprocessable("EVIDENCE_NOT_READY",
                    "证据包未达到完工条件：" + String.join("；", problems));
        }
        return new CompletableEvidence(latest, items, resultRows, materialRows);
    }

    /** 完工准入校验通过后的证据包快照。 */
    public record CompletableEvidence(
            EvidencePackage evidencePackage,
            List<TemplateItem> items,
            List<EvidenceResult> results,
            List<EvidenceMaterial> materials) {
    }

    // ---------- 更正附录 ----------

    @Transactional
    public AppendixView createAppendix(String equipmentId, int cycleNo, CreateAppendixRequest req) {
        lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + cycleNo + "|" + req.operatorId() + "|" + req.note();
        return idempotency.execute(req.requestId(), "CREATE_APPENDIX", fingerprint,
                AppendixView.class, () -> {
                    MaintenanceRecord record = equipmentRepository.findMaintenanceByCycle(equipmentId, cycleNo)
                            .orElseThrow(() -> ApiException.unprocessable("CYCLE_NOT_COMPLETED",
                                    "仅已完成周期可创建更正附录：周期 " + cycleNo + " 尚未完成，不能重开或提前附录"));
                    Instant now = clock.instant();
                    long id = evidenceRepository.insertAppendix(equipmentId, cycleNo,
                            req.operatorId(), req.note(), req.requestId(), now);
                    return new AppendixView(id, record.cycleNo(), req.operatorId(), req.note(), now);
                });
    }

    @Transactional(readOnly = true)
    public List<AppendixView> listAppendices(String equipmentId, int cycleNo) {
        requireEquipment(equipmentId);
        equipmentRepository.findMaintenanceByCycle(equipmentId, cycleNo)
                .orElseThrow(() -> ApiException.notFound("CYCLE_NOT_FOUND",
                        "周期不存在或尚未完成：" + cycleNo));
        return evidenceRepository.listAppendices(equipmentId, cycleNo).stream()
                .map(this::toAppendixView)
                .toList();
    }

    private AppendixView toAppendixView(EvidenceAppendix appendix) {
        return new AppendixView(appendix.id(), appendix.cycleNo(), appendix.operatorId(),
                appendix.note(), appendix.createdAt());
    }

    // ---------- 内部 ----------

    private com.example.starter.maintenance.domain.Equipment lockEquipment(String equipmentId) {
        return equipmentRepository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND",
                        "设备不存在：" + equipmentId));
    }

    private void requireEquipment(String equipmentId) {
        equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND",
                        "设备不存在：" + equipmentId));
    }

    private ApiException versionConflict(long expected, long actual) {
        return ApiException.conflict("VERSION_CONFLICT",
                "设备版本冲突：期望 " + expected + "，当前 " + actual);
    }
}
