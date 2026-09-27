package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceConfirmation;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.MaintenanceTemplate;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.EquipmentRepository;
import com.example.starter.maintenance.store.EvidenceRepository;
import com.example.starter.maintenance.store.TemplateRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 */
@Service
public class EquipmentTxService {

    private final EquipmentRepository repository;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final EvidenceTxService evidenceTxService;
    private final EvidenceRepository evidenceRepository;
    private final TemplateRepository templateRepository;

    public EquipmentTxService(EquipmentRepository repository, IdempotencyService idempotency, Clock clock,
                              EvidenceTxService evidenceTxService,
                              EvidenceRepository evidenceRepository,
                              TemplateRepository templateRepository) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.clock = clock;
        this.evidenceTxService = evidenceTxService;
        this.evidenceRepository = evidenceRepository;
        this.templateRepository = templateRepository;
    }

    // ---------- 登记设备 ----------

    @Transactional
    public EquipmentResponse register(RegisterEquipmentRequest req) {
        String fingerprint = req.equipmentId() + "|" + req.maintenancePeriodMinutes();
        return idempotency.execute(req.requestId(), "REGISTER_EQUIPMENT", fingerprint,
                EquipmentResponse.class, () -> {
                    if (repository.findEquipment(req.equipmentId()).isPresent()) {
                        throw ApiException.conflict("EQUIPMENT_EXISTS", "设备已存在：" + req.equipmentId());
                    }
                    repository.insertEquipment(req.equipmentId(), req.maintenancePeriodMinutes(), clock.instant());
                    return new EquipmentResponse(req.equipmentId(), req.maintenancePeriodMinutes(), 1L);
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

    // ---------- 完成保养 ----------

    @Transactional
    public MaintenanceResponse completeMaintenance(String equipmentId, CompleteMaintenanceRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.readingId() + "|" + req.anchorRevisionNo()
                + "|" + req.expectedPackageVersion() + "|" + req.expectedTemplateVersion()
                + "|" + req.confirmerId() + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "COMPLETE_MAINTENANCE", fingerprint,
                MaintenanceResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    int cycleNo = repository.countMaintenance(equipmentId) + 1;
                    Reading anchor = repository.findReading(equipmentId, req.readingId())
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "锚点读数不存在：" + req.readingId()));
                    if (anchor.revisionNo() != req.anchorRevisionNo()) {
                        throw ApiException.conflict("ANCHOR_REVISION_CONFLICT",
                                "锚点修订号与读数当前修订号不一致：期望 " + req.anchorRevisionNo()
                                        + "，当前 " + anchor.revisionNo());
                    }
                    Optional<MaintenanceRecord> last = repository.findLastMaintenanceRecord(equipmentId);
                    if (last.isPresent() && !anchor.sampledAt().isAfter(last.get().anchorSampledAt())) {
                        throw ApiException.unprocessable("ANCHOR_TIME_NOT_LATER",
                                "保养锚点时间必须晚于上次保养锚点时间");
                    }

                    // 证据准入门禁：当前周期证据包须齐备（每项恰一个 PASS、至少一条材料摘要、无撤销材料）
                    EvidenceTxService.CompletableEvidence evidence =
                            evidenceTxService.requireCompletable(equipmentId, cycleNo);
                    int fixedTemplateVersion = evidence.evidencePackage().templateVersion();
                    int latestPackageVersion = evidence.evidencePackage().packageVersion();
                    if (req.expectedPackageVersion() != latestPackageVersion) {
                        throw ApiException.conflict("EVIDENCE_VERSION_CONFLICT",
                                "确认时证据包版本已变化：期望确认版本 " + req.expectedPackageVersion()
                                        + "，当前最新版本 " + latestPackageVersion);
                    }
                    if (req.expectedTemplateVersion() != fixedTemplateVersion) {
                        throw ApiException.conflict("TEMPLATE_VERSION_CONFLICT",
                                "确认时模板版本不一致：期望 " + req.expectedTemplateVersion()
                                        + "，证据包固化版本 " + fixedTemplateVersion);
                    }
                    String submitterId = evidence.evidencePackage().submittedBy();
                    if (req.confirmerId().equals(submitterId)) {
                        throw ApiException.unprocessable("CONFIRMER_IS_SUBMITTER",
                                "提交人不能确认完工：确认人须为另一维护人员（提交人 " + submitterId + "）");
                    }

                    // OVERDUE 门禁：设备“当前”是否已 OVERDUE 以最新读数的本轮运行分钟判定，
                    // 达到当前模板 OVERDUE 阈值则禁止完工（阈值在周期证据提交后模板修订不影响在途周期判定）
                    long anchorBase = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
                    Optional<MaintenanceTemplate> currentTemplate =
                            templateRepository.findLatestTemplate(equipmentId);
                    long currentRunMinutes = repository.findLatestReading(equipmentId)
                            .map(Reading::cumulativeMinutes).orElse(0L) - anchorBase;
                    if (currentTemplate.isPresent()
                            && currentRunMinutes >= currentTemplate.get().overdueThresholdMinutes()) {
                        throw ApiException.unprocessable("EQUIPMENT_OVERDUE",
                                "设备当前已 OVERDUE，不可完成保养：本轮实际运行 " + currentRunMinutes
                                        + " 分钟，OVERDUE 阈值 "
                                        + currentTemplate.get().overdueThresholdMinutes()
                                        + " 分钟，超出 " + (currentRunMinutes
                                        - currentTemplate.get().overdueThresholdMinutes()) + " 分钟");
                    }

                    Instant now = clock.instant();
                    long maintenanceId = repository.insertMaintenance(equipmentId, cycleNo, req.readingId(),
                            req.anchorRevisionNo(), fixedTemplateVersion, latestPackageVersion,
                            anchor.sampledAt(), anchor.cumulativeMinutes(), req.requestId(), now);
                    evidenceRepository.insertConfirmation(new MaintenanceConfirmation(
                            maintenanceId, equipmentId, cycleNo, fixedTemplateVersion, latestPackageVersion,
                            submitterId, req.confirmerId(), now), req.requestId());
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, cycleNo, req.readingId(),
                            req.anchorRevisionNo(), fixedTemplateVersion, latestPackageVersion,
                            submitterId, req.confirmerId(), anchor.sampledAt(),
                            anchor.cumulativeMinutes(), now, equipment.version() + 1);
                });
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public StatusResponse getStatus(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        Optional<Reading> latest = repository.findLatestReading(equipmentId);
        Optional<MaintenanceRecord> last = repository.findLastMaintenanceRecord(equipmentId);
        Optional<MaintenanceTemplate> template = templateRepository.findLatestTemplate(equipmentId);
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long runMinutes = latestCumulative - anchorCumulative;
        // 区间左闭右开：[0, 周期) OK；[周期, 阈值) DUE；达到阈值 OVERDUE。无模板时仅有 OK/DUE。
        String status;
        long overdueBy = 0L;
        Long threshold = template.map(MaintenanceTemplate::overdueThresholdMinutes).orElse(null);
        if (threshold != null && runMinutes >= threshold) {
            status = "OVERDUE";
            overdueBy = runMinutes - threshold;
        } else {
            status = runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK";
        }
        long remaining = equipment.maintenancePeriodMinutes() - runMinutes;
        int currentCycleNo = repository.countMaintenance(equipmentId) + 1;
        return new StatusResponse(equipmentId, equipment.version(), equipment.maintenancePeriodMinutes(),
                threshold,
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                runMinutes, remaining, overdueBy, currentCycleNo, status);
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
                .map(record -> {
                    var confirmation = evidenceRepository
                            .findConfirmationByMaintenance(record.maintenanceId()).orElse(null);
                    return new MaintenanceResponse(record.maintenanceId(), equipmentId, record.cycleNo(),
                            record.readingId(), record.anchorRevisionNo(),
                            record.templateVersion(), record.packageVersion(),
                            confirmation == null ? null : confirmation.submitterId(),
                            confirmation == null ? null : confirmation.confirmerId(),
                            record.anchorSampledAt(), record.anchorCumulativeMinutes(),
                            record.completedAt(), 0L);
                })
                .toList();
    }

    // ---------- 内部规则 ----------

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
}
