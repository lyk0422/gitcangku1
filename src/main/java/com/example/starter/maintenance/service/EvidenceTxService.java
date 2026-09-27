package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.ErrorDetail;
import com.example.starter.maintenance.api.dto.AppendixResponse;
import com.example.starter.maintenance.api.dto.ConfirmCompletionRequest;
import com.example.starter.maintenance.api.dto.ConfirmationResponse;
import com.example.starter.maintenance.api.dto.CreateAppendixRequest;
import com.example.starter.maintenance.api.dto.CreateTemplateRequest;
import com.example.starter.maintenance.api.dto.DiagnosticsResponse;
import com.example.starter.maintenance.api.dto.EvidenceItemView;
import com.example.starter.maintenance.api.dto.EvidenceMaterialView;
import com.example.starter.maintenance.api.dto.EvidencePackageResponse;
import com.example.starter.maintenance.api.dto.EvidencePackageSummary;
import com.example.starter.maintenance.api.dto.MaterialBatchResponse;
import com.example.starter.maintenance.api.dto.MaterialSummaryInput;
import com.example.starter.maintenance.api.dto.RegisterMaterialRequest;
import com.example.starter.maintenance.api.dto.ReviseTemplateRequest;
import com.example.starter.maintenance.api.dto.RevokeMaterialRequest;
import com.example.starter.maintenance.api.dto.SubmitEvidenceRequest;
import com.example.starter.maintenance.api.dto.TemplateItemInput;
import com.example.starter.maintenance.api.dto.TemplateItemView;
import com.example.starter.maintenance.api.dto.TemplateResponse;
import com.example.starter.maintenance.api.dto.ItemResultInput;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.EvidenceItemResult;
import com.example.starter.maintenance.domain.EvidenceMaterial;
import com.example.starter.maintenance.domain.EvidencePackage;
import com.example.starter.maintenance.domain.MaintenanceAppendix;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.MaterialBatch;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.domain.TemplateItem;
import com.example.starter.maintenance.store.EquipmentRepository;
import com.example.starter.maintenance.store.EvidenceRepository;

/**
 * 保养证据包事务业务服务：模板版本管理、材料批次登记/撤销、证据包提交、
 * 双人完工确认与更正附录。写操作在设备/模板/材料行锁内执行，先校验完整最终状态，
 * 再在同一事务提交；任一规则失败抛异常整体回滚，查询不到半成品。
 */
@Service
public class EvidenceTxService {

    /** OVERDUE 倍数：本轮运行分钟达到保养周期 2 倍即为 OVERDUE，禁止完成保养。 */
    public static final long OVERDUE_MULTIPLE = 2;

    private static final Pattern BATCH_CODE_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{2,63}$");

    private final EvidenceRepository evidenceRepository;
    private final EquipmentRepository equipmentRepository;
    private final IdempotencyService idempotency;
    private final EvidenceRules rules;
    private final Clock clock;

    public EvidenceTxService(EvidenceRepository evidenceRepository,
                             EquipmentRepository equipmentRepository,
                             IdempotencyService idempotency,
                             EvidenceRules rules,
                             Clock clock) {
        this.evidenceRepository = evidenceRepository;
        this.equipmentRepository = equipmentRepository;
        this.idempotency = idempotency;
        this.rules = rules;
        this.clock = clock;
    }

    // ---------- 保养模板 ----------

    @Transactional
    public TemplateResponse createTemplate(CreateTemplateRequest req) {
        List<TemplateItemInput> items = req.items();
        validateTemplateItems(items);
        String fingerprint = req.templateId() + "|" + canonicalItems(items);
        return idempotency.execute(req.requestId(), "CREATE_TEMPLATE", fingerprint,
                TemplateResponse.class, () -> {
                    Optional<Integer> existing = evidenceRepository.lockTemplateHead(req.templateId());
                    if (existing.isPresent()) {
                        throw ApiException.conflict("TEMPLATE_EXISTS", "模板已存在：" + req.templateId());
                    }
                    Instant now = clock.instant();
                    evidenceRepository.insertTemplateHead(req.templateId(), now);
                    insertItemRows(req.templateId(), 1, items);
                    return new TemplateResponse(req.templateId(), 1, toItemViews(1, items));
                });
    }

    @Transactional
    public TemplateResponse reviseTemplate(String templateId, ReviseTemplateRequest req) {
        List<TemplateItemInput> items = req.items();
        validateTemplateItems(items);
        String fingerprint = templateId + "|" + canonicalItems(items);
        return idempotency.execute(req.requestId(), "REVISE_TEMPLATE", fingerprint,
                TemplateResponse.class, () -> {
                    Integer currentVersion = evidenceRepository.lockTemplateHead(templateId)
                            .orElseThrow(() -> ApiException.notFound("TEMPLATE_NOT_FOUND",
                                    "模板不存在：" + templateId));
                    int newVersion = currentVersion + 1;
                    insertItemRows(templateId, newVersion, items);
                    evidenceRepository.updateTemplateCurrentVersion(templateId, newVersion);
                    return new TemplateResponse(templateId, newVersion, toItemViews(newVersion, items));
                });
    }

    @Transactional(readOnly = true)
    public TemplateResponse getTemplate(String templateId) {
        Integer currentVersion = evidenceRepository.findTemplateCurrentVersion(templateId)
                .orElseThrow(() -> ApiException.notFound("TEMPLATE_NOT_FOUND", "模板不存在：" + templateId));
        List<TemplateItemView> views = evidenceRepository.listTemplateItems(templateId, currentVersion)
                .stream()
                .map(item -> new TemplateItemView(item.itemCode(), item.itemName(),
                        item.required(), item.sortOrder()))
                .toList();
        return new TemplateResponse(templateId, currentVersion, views);
    }

    // ---------- 材料批次 ----------

    @Transactional
    public MaterialBatchResponse registerMaterial(RegisterMaterialRequest req) {
        if (!BATCH_CODE_PATTERN.matcher(req.batchCode()).matches()) {
            throw ApiException.unprocessable("MATERIAL_BAD_FORMAT",
                    "材料批次编码格式非法：" + req.batchCode()
                            + "（须以字母或数字开头，仅含字母数字与连字符，长度 3-64）");
        }
        String fingerprint = req.batchCode() + "|" + req.materialName();
        return idempotency.execute(req.requestId(), "REGISTER_MATERIAL", fingerprint,
                MaterialBatchResponse.class, () -> {
                    if (evidenceRepository.findMaterialBatch(req.batchCode()).isPresent()) {
                        throw ApiException.conflict("MATERIAL_EXISTS",
                                "材料批次已存在：" + req.batchCode());
                    }
                    Instant now = clock.instant();
                    evidenceRepository.insertMaterialBatch(req.batchCode(), req.materialName(),
                            req.requestId(), now);
                    return new MaterialBatchResponse(req.batchCode(), req.materialName(),
                            "ACTIVE", now, null, null);
                });
    }

    @Transactional
    public MaterialBatchResponse revokeMaterial(String batchCode, RevokeMaterialRequest req) {
        String fingerprint = batchCode + "|" + req.reason();
        return idempotency.execute(req.requestId(), "REVOKE_MATERIAL", fingerprint,
                MaterialBatchResponse.class, () -> {
                    MaterialBatch batch = evidenceRepository.lockMaterialBatch(batchCode)
                            .orElseThrow(() -> ApiException.notFound("MATERIAL_NOT_FOUND",
                                    "材料批次不存在：" + batchCode));
                    if ("REVOKED".equals(batch.status())) {
                        throw ApiException.conflict("MATERIAL_ALREADY_REVOKED",
                                "材料批次已撤销，撤销为终态：" + batchCode);
                    }
                    Instant now = clock.instant();
                    evidenceRepository.revokeMaterialBatch(batchCode, req.reason(), now);
                    return new MaterialBatchResponse(batchCode, batch.materialName(), "REVOKED",
                            batch.createdAt(), now, req.reason());
                });
    }

    @Transactional(readOnly = true)
    public MaterialBatchResponse getMaterial(String batchCode) {
        MaterialBatch batch = evidenceRepository.findMaterialBatch(batchCode)
                .orElseThrow(() -> ApiException.notFound("MATERIAL_NOT_FOUND",
                        "材料批次不存在：" + batchCode));
        return new MaterialBatchResponse(batch.batchCode(), batch.materialName(), batch.status(),
                batch.createdAt(), batch.revokedAt(), batch.revokeReason());
    }

    // ---------- 证据包提交 ----------

    @Transactional
    public EvidencePackageResponse submitEvidence(String equipmentId, SubmitEvidenceRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.expectedVersion() + "|" + req.submittedBy()
                + "|" + canonicalItemResults(req.itemResults()) + "|"
                + canonicalMaterials(req.materials());
        return idempotency.execute(req.requestId(), "SUBMIT_EVIDENCE", fingerprint,
                EvidencePackageResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    String templateId = equipment.templateId();
                    if (templateId == null) {
                        throw ApiException.unprocessable("EQUIPMENT_TEMPLATE_REQUIRED",
                                "设备未分配保养模板，无法提交证据包：" + equipmentId);
                    }
                    long cycleNo = equipment.currentCycleNo();
                    if (evidenceRepository.findConfirmation(equipmentId, cycleNo).isPresent()) {
                        throw ApiException.conflict("CYCLE_ALREADY_CONFIRMED",
                                "当前保养周期已完工确认，证据包不可变：周期 " + cycleNo);
                    }

                    // 锁定模板头：提交期间模板版本不得变化
                    Integer templateVersion = evidenceRepository.lockTemplateHead(templateId)
                            .orElseThrow(() -> ApiException.unprocessable("TEMPLATE_NOT_FOUND",
                                    "设备引用的模板不存在：" + templateId));
                    Map<String, TemplateItem> templateItems = evidenceRepository
                            .listTemplateItems(templateId, templateVersion).stream()
                            .collect(Collectors.toMap(TemplateItem::itemCode, item -> item,
                                    (a, b) -> a, HashMap::new));

                    // 先校验完整最终包：检查项结果
                    Set<String> seenItemCodes = new HashSet<>();
                    List<EvidenceItemResult> resultRows = new ArrayList<>();
                    for (ItemResultInput input : req.itemResults()) {
                        if (!seenItemCodes.add(input.itemCode())) {
                            throw ApiException.unprocessable("EVIDENCE_ITEM_DUPLICATE",
                                    "同一检查项在一次提交中出现多次：" + input.itemCode());
                        }
                        TemplateItem templateItem = templateItems.get(input.itemCode());
                        if (templateItem == null) {
                            throw ApiException.unprocessable("EVIDENCE_ITEM_NOT_IN_TEMPLATE",
                                    "检查项不属于设备当前模板（版本 " + templateVersion + "）："
                                            + input.itemCode());
                        }
                        if (!"PASS".equals(input.result()) && !"FAIL".equals(input.result())) {
                            throw ApiException.unprocessable("EVIDENCE_RESULT_INVALID",
                                    "检查结果仅允许 PASS 或 FAIL：" + input.itemCode()
                                            + "=" + input.result());
                        }
                        resultRows.add(new EvidenceItemResult(0L, input.itemCode(),
                                templateItem.itemName(), templateItem.required(),
                                templateItem.sortOrder(), input.result(), input.note()));
                    }

                    // 先校验完整最终包：材料批次摘要
                    if (req.materials().isEmpty()) {
                        throw ApiException.unprocessable("EVIDENCE_MATERIAL_REQUIRED",
                                "证据包至少需要一个材料批次摘要");
                    }
                    Set<String> seenBatches = new HashSet<>();
                    Set<String> cycleBatches = new HashSet<>(
                            evidenceRepository.listCycleMaterialBatchCodes(equipmentId, cycleNo));
                    List<EvidenceMaterial> materialRows = new ArrayList<>();
                    for (MaterialSummaryInput input : req.materials()) {
                        if (!seenBatches.add(input.batchCode())) {
                            throw ApiException.unprocessable("EVIDENCE_MATERIAL_DUPLICATE",
                                    "同一材料批次在一次提交中出现多次：" + input.batchCode());
                        }
                        if (cycleBatches.contains(input.batchCode())) {
                            throw ApiException.unprocessable("EVIDENCE_MATERIAL_ALREADY_USED",
                                    "材料批次摘要在同一设备周期已提交过，周期内不可重复：周期 "
                                            + cycleNo + "，批次 " + input.batchCode());
                        }
                        MaterialBatch batch = evidenceRepository.findMaterialBatch(input.batchCode())
                                .orElseThrow(() -> ApiException.unprocessable("MATERIAL_NOT_FOUND",
                                        "材料批次不存在：" + input.batchCode()));
                        if ("REVOKED".equals(batch.status())) {
                            throw ApiException.unprocessable("MATERIAL_REVOKED",
                                    "材料批次已撤销，不可引用：" + input.batchCode());
                        }
                        materialRows.add(new EvidenceMaterial(0L, input.batchCode(),
                                batch.materialName(), input.quantity(), input.unit(),
                                input.summary()));
                    }

                    int newPackageVersion = evidenceRepository.findLatestPackage(equipmentId, cycleNo)
                            .map(EvidencePackage::packageVersion).orElse(0) + 1;
                    Instant now = clock.instant();
                    long packageId = evidenceRepository.insertEvidencePackage(equipmentId, cycleNo,
                            newPackageVersion, templateId, templateVersion, req.submittedBy(),
                            req.requestId(), now);
                    for (EvidenceItemResult row : resultRows) {
                        evidenceRepository.insertItemResult(packageId, row);
                    }
                    for (EvidenceMaterial row : materialRows) {
                        evidenceRepository.insertEvidenceMaterial(packageId, equipmentId, cycleNo, row);
                    }
                    equipmentRepository.incrementVersion(equipmentId);
                    return toPackageResponse(equipmentId, cycleNo, newPackageVersion, templateId,
                            templateVersion, req.submittedBy(), now, packageId,
                            equipment.version() + 1);
                });
    }

    // ---------- 双人完工确认 ----------

    @Transactional
    public ConfirmationResponse confirmCompletion(String equipmentId, ConfirmCompletionRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.expectedVersion() + "|" + req.confirmedBy()
                + "|" + req.packageVersion() + "|" + req.templateVersion() + "|"
                + req.readingId() + "|" + req.anchorRevisionNo();
        return idempotency.execute(req.requestId(), "CONFIRM_COMPLETION", fingerprint,
                ConfirmationResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    String templateId = equipment.templateId();
                    if (templateId == null) {
                        throw ApiException.unprocessable("EQUIPMENT_TEMPLATE_REQUIRED",
                                "设备未分配保养模板，不支持双人完工确认：" + equipmentId);
                    }
                    long cycleNo = equipment.currentCycleNo();
                    if (evidenceRepository.findConfirmation(equipmentId, cycleNo).isPresent()) {
                        throw ApiException.conflict("CYCLE_ALREADY_CONFIRMED",
                                "当前保养周期已完工确认，不可重复确认：周期 " + cycleNo);
                    }

                    // 证据包版本与模板版本校验：确认时发生变化则 409
                    EvidencePackage pkg = evidenceRepository.findLatestPackage(equipmentId, cycleNo)
                            .orElseThrow(() -> ApiException.conflict("EVIDENCE_PACKAGE_MISSING",
                                    "当前周期尚无证据包，无法确认完工：周期 " + cycleNo));
                    if (pkg.packageVersion() != req.packageVersion()) {
                        throw ApiException.conflict("PACKAGE_VERSION_CONFLICT",
                                "证据包版本冲突：请求确认版本 " + req.packageVersion()
                                        + "，当前最新版本 " + pkg.packageVersion());
                    }
                    Integer currentTemplateVersion = evidenceRepository.findTemplateCurrentVersion(templateId)
                            .orElseThrow(() -> ApiException.conflict("TEMPLATE_MISSING",
                                    "设备引用的模板不存在：" + templateId));
                    if (currentTemplateVersion != req.templateVersion()
                            || pkg.templateVersion() != req.templateVersion()) {
                        throw ApiException.conflict("TEMPLATE_VERSION_CONFLICT",
                                "模板版本冲突：请求版本 " + req.templateVersion()
                                        + "，模板当前版本 " + currentTemplateVersion
                                        + "，证据包固化版本 " + pkg.templateVersion());
                    }

                    // 双人规则：确认人不得为提交人
                    if (req.confirmedBy().equals(pkg.submittedBy())) {
                        throw ApiException.unprocessable("CONFIRMER_IS_SUBMITTER",
                                "提交人不能确认本人提交的证据包完工：提交人/确认人均为 "
                                        + req.confirmedBy());
                    }

                    // 证据包门禁：缺失/失败/撤销/OVERDUE 返回 422 并列出明细
                    EvidenceDiagnostics diagnostics = rules.diagnose(equipment);
                    if (!diagnostics.readyForCompletion()) {
                        throw ApiException.unprocessable("EVIDENCE_NOT_READY",
                                "证据包未齐备或设备已 OVERDUE，不能完成保养",
                                buildGateDetails(equipment, diagnostics));
                    }

                    // 锚点读数规则（与既有完成保养一致）
                    Reading anchor = equipmentRepository.findReading(equipmentId, req.readingId())
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "锚点读数不存在：" + req.readingId()));
                    if (anchor.revisionNo() != req.anchorRevisionNo()) {
                        throw ApiException.conflict("ANCHOR_REVISION_CONFLICT",
                                "锚点修订号与读数当前修订号不一致：期望 " + req.anchorRevisionNo()
                                        + "，当前 " + anchor.revisionNo());
                    }
                    Optional<MaintenanceRecord> last = equipmentRepository.findLastMaintenance(equipmentId);
                    if (last.isPresent() && !anchor.sampledAt().isAfter(last.get().anchorSampledAt())) {
                        throw ApiException.unprocessable("ANCHOR_TIME_NOT_LATER",
                                "保养锚点时间必须晚于上次保养锚点时间");
                    }

                    // 原子完成：保养记录 + 双人确认 + 周期重置 + 版本加一
                    Instant now = clock.instant();
                    long maintenanceId = equipmentRepository.insertMaintenance(equipmentId,
                            req.readingId(), req.anchorRevisionNo(), anchor.sampledAt(),
                            anchor.cumulativeMinutes(), req.requestId(), now);
                    long confirmationId = evidenceRepository.insertConfirmation(equipmentId, cycleNo,
                            pkg.packageVersion(), currentTemplateVersion, req.confirmedBy(),
                            req.requestId(), maintenanceId, now);
                    equipmentRepository.incrementVersion(equipmentId);
                    equipmentRepository.incrementCycle(equipmentId);
                    return new ConfirmationResponse(confirmationId, maintenanceId, equipmentId,
                            cycleNo, cycleNo + 1, pkg.packageVersion(), currentTemplateVersion,
                            req.confirmedBy(), now, equipment.version() + 1);
                });
    }

    // ---------- 更正附录 ----------

    @Transactional
    public AppendixResponse createAppendix(String equipmentId, long maintenanceId,
                                           CreateAppendixRequest req) {
        String fingerprint = equipmentId + "|" + maintenanceId + "|" + req.createdBy()
                + "|" + req.content();
        return idempotency.execute(req.requestId(), "CREATE_APPENDIX", fingerprint,
                AppendixResponse.class, () -> {
                    equipmentRepository.findMaintenance(equipmentId, maintenanceId)
                            .orElseThrow(() -> ApiException.notFound("MAINTENANCE_NOT_FOUND",
                                    "保养记录不存在：" + maintenanceId));
                    Instant now = clock.instant();
                    long appendixId = evidenceRepository.insertAppendix(maintenanceId, equipmentId,
                            req.createdBy(), req.content(), req.requestId(), now);
                    return new AppendixResponse(appendixId, maintenanceId, equipmentId,
                            req.createdBy(), req.content(), now);
                });
    }

    @Transactional(readOnly = true)
    public List<AppendixResponse> listAppendices(String equipmentId, long maintenanceId) {
        equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND",
                        "设备不存在：" + equipmentId));
        equipmentRepository.findMaintenance(equipmentId, maintenanceId)
                .orElseThrow(() -> ApiException.notFound("MAINTENANCE_NOT_FOUND",
                        "保养记录不存在：" + maintenanceId));
        return evidenceRepository.listAppendices(maintenanceId).stream()
                .map(this::toAppendixResponse)
                .toList();
    }

    // ---------- 证据包查询（明细/历史/诊断，读取不改变状态） ----------

    @Transactional(readOnly = true)
    public EvidencePackageResponse getEvidencePackage(String equipmentId, Long cycleNo,
                                                      Integer packageVersion) {
        Equipment equipment = equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND",
                        "设备不存在：" + equipmentId));
        EvidencePackage pkg;
        if (cycleNo == null) {
            pkg = evidenceRepository.findLatestPackage(equipmentId, equipment.currentCycleNo())
                    .orElseThrow(() -> ApiException.notFound("EVIDENCE_PACKAGE_NOT_FOUND",
                            "当前周期尚无证据包：周期 " + equipment.currentCycleNo()));
        } else {
            pkg = evidenceRepository.findPackage(equipmentId, cycleNo, packageVersion)
                    .orElseThrow(() -> ApiException.notFound("EVIDENCE_PACKAGE_NOT_FOUND",
                            "证据包版本不存在：周期 " + cycleNo + "，版本 " + packageVersion));
        }
        return toPackageResponse(pkg, 0L);
    }

    @Transactional(readOnly = true)
    public List<EvidencePackageSummary> listEvidenceHistory(String equipmentId) {
        Equipment equipment = equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND",
                        "设备不存在：" + equipmentId));
        List<EvidencePackageSummary> result = new ArrayList<>();
        for (EvidencePackage pkg : evidenceRepository.listPackages(equipmentId)) {
            int itemCount = evidenceRepository.listItemResults(pkg.packageId()).size();
            int materialCount = evidenceRepository.listEvidenceMaterials(pkg.packageId()).size();
            result.add(new EvidencePackageSummary(pkg.packageId(), pkg.equipmentId(), pkg.cycleNo(),
                    pkg.packageVersion(), pkg.templateId(), pkg.templateVersion(),
                    pkg.submittedBy(), pkg.submittedAt(), itemCount, materialCount));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public DiagnosticsResponse getDiagnostics(String equipmentId) {
        Equipment equipment = equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND",
                        "设备不存在：" + equipmentId));
        EvidenceDiagnostics d = rules.diagnose(equipment);
        long period = equipment.maintenancePeriodMinutes();
        String status = d.overdue() ? "OVERDUE"
                : d.runMinutes() >= period ? "DUE" : "OK";
        Integer pkgTemplateVersion = d.latestPackage() == null
                ? null : d.latestPackage().templateVersion();
        Integer pkgVersion = d.latestPackage() == null
                ? null : d.latestPackage().packageVersion();
        return new DiagnosticsResponse(equipmentId, equipment.currentCycleNo(), pkgVersion,
                equipment.templateId(), d.currentTemplateVersion(), pkgTemplateVersion, status,
                d.runMinutes(), period, period - d.runMinutes(), d.requiredItemCount(),
                d.passedRequiredItemCount(), d.missingRequiredItems(), d.failedItems(),
                d.revokedMaterials(), d.materialCount(), d.readyForCompletion());
    }

    // ---------- 内部辅助 ----------

    private Equipment lockEquipment(String equipmentId) {
        return equipmentRepository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND",
                        "设备不存在：" + equipmentId));
    }

    private void checkVersion(Equipment equipment, long expectedVersion) {
        if (equipment.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "设备版本冲突：期望 " + expectedVersion + "，当前 " + equipment.version());
        }
    }

    private void validateTemplateItems(List<TemplateItemInput> items) {
        Set<String> codes = new HashSet<>();
        for (TemplateItemInput item : items) {
            if (!codes.add(item.itemCode())) {
                throw ApiException.unprocessable("TEMPLATE_ITEM_DUPLICATE",
                        "模板检查项编码重复：" + item.itemCode());
            }
        }
    }

    private void insertItemRows(String templateId, int version, List<TemplateItemInput> items) {
        for (TemplateItemInput item : items) {
            evidenceRepository.insertTemplateItem(templateId, version,
                    new TemplateItem(item.itemCode(), item.itemName(), item.required(),
                            item.sortOrder()));
        }
    }

    private List<TemplateItemView> toItemViews(int version, List<TemplateItemInput> items) {
        return items.stream()
                .map(item -> new TemplateItemView(item.itemCode(), item.itemName(),
                        item.required(), item.sortOrder()))
                .sorted((a, b) -> {
                    int byOrder = Integer.compare(a.sortOrder(), b.sortOrder());
                    return byOrder != 0 ? byOrder : a.itemCode().compareTo(b.itemCode());
                })
                .toList();
    }

    private EvidencePackageResponse toPackageResponse(EvidencePackage pkg, long equipmentVersion) {
        return toPackageResponse(pkg.equipmentId(), pkg.cycleNo(), pkg.packageVersion(),
                pkg.templateId(), pkg.templateVersion(), pkg.submittedBy(), pkg.submittedAt(),
                pkg.packageId(), equipmentVersion);
    }

    private EvidencePackageResponse toPackageResponse(String equipmentId, long cycleNo, int packageVersion,
                                                      String templateId, int templateVersion,
                                                      String submittedBy, Instant submittedAt,
                                                      long packageId, long equipmentVersion) {
        List<EvidenceItemView> itemViews = evidenceRepository.listItemResults(packageId).stream()
                .map(row -> new EvidenceItemView(row.itemCode(), row.itemName(), row.required(),
                        row.sortOrder(), row.result(), row.note()))
                .toList();
        List<EvidenceMaterialView> materialViews = evidenceRepository.listEvidenceMaterials(packageId)
                .stream()
                .map(row -> new EvidenceMaterialView(row.batchCode(), row.materialName(),
                        row.quantity(), row.unit(), row.summary()))
                .toList();
        return new EvidencePackageResponse(packageId, equipmentId, cycleNo, packageVersion,
                templateId, templateVersion, submittedBy, submittedAt, itemViews, materialViews,
                equipmentVersion);
    }

    private AppendixResponse toAppendixResponse(MaintenanceAppendix appendix) {
        return new AppendixResponse(appendix.appendixId(), appendix.maintenanceId(),
                appendix.equipmentId(), appendix.createdBy(), appendix.content(), appendix.createdAt());
    }

    /** 门禁 422 明细：缺失必检项、FAIL 项、已撤销材料、OVERDUE 时长差额。 */
    private List<ErrorDetail> buildGateDetails(Equipment equipment, EvidenceDiagnostics d) {
        List<ErrorDetail> details = new ArrayList<>();
        for (String code : d.missingRequiredItems()) {
            details.add(ErrorDetail.of("MISSING_REQUIRED_ITEM", code,
                    "必检项缺少 PASS 结果：" + code));
        }
        for (String code : d.failedItems()) {
            details.add(ErrorDetail.of("FAILED_ITEM", code, "检查项结果为 FAIL：" + code));
        }
        for (String batch : d.revokedMaterials()) {
            details.add(ErrorDetail.of("REVOKED_MATERIAL", batch,
                    "证据包引用了已撤销（或不存在）的材料批次：" + batch));
        }
        if (d.materialCount() == 0) {
            details.add(ErrorDetail.of("MATERIAL_REQUIRED", null, "证据包缺少材料批次摘要（至少 1 个）"));
        }
        if (d.overdue()) {
            long overdueLimit = equipment.maintenancePeriodMinutes() * OVERDUE_MULTIPLE;
            details.add(ErrorDetail.ofQuantity("EQUIPMENT_OVERDUE", equipment.equipmentId(),
                    "设备已 OVERDUE，禁止完成保养", d.runMinutes(), overdueLimit));
        }
        return details;
    }

    /** 模板检查项指纹（创建/修订模板幂等判定用，门面层复用）。 */
    public static String canonicalItems(List<TemplateItemInput> items) {
        return items.stream()
                .map(item -> item.itemCode() + ":" + item.itemName() + ":" + item.required()
                        + ":" + item.sortOrder())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private String canonicalItemResults(List<ItemResultInput> items) {
        return items.stream()
                .map(item -> item.itemCode() + ":" + item.result() + ":" + item.note())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private String canonicalMaterials(List<MaterialSummaryInput> materials) {
        return materials.stream()
                .map(m -> m.batchCode() + ":" + m.quantity() + ":" + m.unit() + ":" + m.summary())
                .sorted()
                .collect(Collectors.joining(","));
    }
}
