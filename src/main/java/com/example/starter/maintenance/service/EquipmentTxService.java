package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.ApplyDeferralRequest;
import com.example.starter.maintenance.api.dto.ApproveDeferralRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.DeferralResponse;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.RejectDeferralRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.domain.Deferral;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
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
                    checkReadingAllowed(equipment);
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
                + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "COMPLETE_MAINTENANCE", fingerprint,
                MaintenanceResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    Reading anchor = repository.findReading(equipmentId, req.readingId())
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "锚点读数不存在：" + req.readingId()));
                    if (anchor.revisionNo() != req.anchorRevisionNo()) {
                        throw ApiException.conflict("ANCHOR_REVISION_CONFLICT",
                                "锚点修订号与读数当前修订号不一致：期望 " + req.anchorRevisionNo()
                                        + "，当前 " + anchor.revisionNo());
                    }
                    Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
                    if (last.isPresent() && !anchor.sampledAt().isAfter(last.get().anchorSampledAt())) {
                        throw ApiException.unprocessable("ANCHOR_TIME_NOT_LATER",
                                "保养锚点时间必须晚于上次保养锚点时间");
                    }
                    Instant now = clock.instant();
                    long maintenanceId = repository.insertMaintenance(equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            req.requestId(), now);
                    // 保养完成即进入新周期：待审批延期自动失效，延期额度归零
                    repository.expirePendingDeferrals(equipmentId, now);
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            now, equipment.version() + 1);
                });
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public StatusResponse getStatus(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        Optional<Reading> latest = repository.findLatestReading(equipmentId);
        Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long runMinutes = latestCumulative - anchorCumulative;
        int cycleNo = repository.countMaintenances(equipmentId);
        long approvedDeferral = repository.sumApprovedDeferMinutes(equipmentId, cycleNo);
        long effectiveThreshold = equipment.maintenancePeriodMinutes() + approvedDeferral;
        boolean overdueLocked = isOverdue(equipment.maintenancePeriodMinutes(), approvedDeferral, runMinutes);
        String status = overdueLocked ? "OVERDUE"
                : (runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK");
        String pendingDeferralKey = repository.findPendingDeferral(equipmentId)
                .map(Deferral::deferKey).orElse(null);
        return new StatusResponse(equipmentId, equipment.version(), equipment.maintenancePeriodMinutes(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                runMinutes, approvedDeferral, effectiveThreshold, pendingDeferralKey,
                overdueLocked, status);
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
                        record.anchorCumulativeMinutes(), record.completedAt(), 0L))
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

    // ---------- 延期申请 ----------

    @Transactional
    public DeferralResponse applyDeferral(String equipmentId, ApplyDeferralRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.deferKey() + "|" + req.deferMinutes()
                + "|" + req.reason() + "|" + req.applicant();
        return idempotency.execute(req.requestId(), "APPLY_DEFERRAL", fingerprint,
                DeferralResponse.class, () -> {
                    int cycleNo = repository.countMaintenances(equipmentId);
                    long period = equipment.maintenancePeriodMinutes();
                    long runMinutes = currentRunMinutes(equipmentId);
                    long approved = repository.sumApprovedDeferMinutes(equipmentId, cycleNo);
                    if (isOverdue(period, approved, runMinutes)) {
                        throw ApiException.conflict("EQUIPMENT_OVERDUE",
                                "设备已超期封锁（本轮运行 " + runMinutes + " 分钟，生效阈值 "
                                        + (period + approved) + " 分钟），须先完成保养");
                    }
                    if (runMinutes < period) {
                        throw ApiException.conflict("EQUIPMENT_NOT_DUE",
                                "设备未处于 DUE（本轮运行 " + runMinutes + " 分钟，保养周期 "
                                        + period + " 分钟），不可申请延期");
                    }
                    if (repository.findPendingDeferral(equipmentId).isPresent()) {
                        throw ApiException.conflict("DEFERRAL_PENDING_EXISTS",
                                "同一设备同时只允许一条待审批延期");
                    }
                    if (repository.findDeferral(equipmentId, req.deferKey()).isPresent()) {
                        throw ApiException.conflict("DEFERRAL_KEY_EXISTS",
                                "延期标识已存在：" + req.deferKey());
                    }
                    checkDeferralLimits(period, approved, req.deferMinutes());
                    long latestCumulative = repository.findLatestReading(equipmentId)
                            .map(Reading::cumulativeMinutes).orElse(0L);
                    long deferralId = repository.insertDeferral(equipmentId, req.deferKey(), cycleNo,
                            req.deferMinutes(), req.reason(), req.applicant(), latestCumulative,
                            req.requestId(), clock.instant());
                    return toResponse(repository.findDeferral(equipmentId, req.deferKey())
                            .orElseThrow(() -> new IllegalStateException("延期记录写入后读取失败："
                                    + deferralId)));
                });
    }

    // ---------- 延期批准 ----------

    @Transactional
    public DeferralResponse approveDeferral(String equipmentId, String deferKey,
                                            ApproveDeferralRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + deferKey + "|" + req.approver();
        return idempotency.execute(req.requestId(), "APPROVE_DEFERRAL", fingerprint,
                DeferralResponse.class, () -> {
                    Deferral deferral = findPendingDeferralForDecision(equipmentId, deferKey);
                    if (deferral.applicant().equals(req.approver())) {
                        throw ApiException.unprocessable("APPROVER_SAME_AS_APPLICANT",
                                "批准人必须与申请人不同：" + req.approver());
                    }
                    long period = equipment.maintenancePeriodMinutes();
                    long approved = repository.sumApprovedDeferMinutes(equipmentId, deferral.cycleNo());
                    checkDeferralLimits(period, approved, deferral.deferMinutes());
                    long originalThreshold = period + approved;
                    long newThreshold = originalThreshold + deferral.deferMinutes();
                    repository.approveDeferral(deferral.deferralId(), req.approver(),
                            originalThreshold, newThreshold, req.requestId(), clock.instant());
                    return toResponse(repository.findDeferral(equipmentId, deferKey)
                            .orElseThrow(() -> new IllegalStateException("延期记录批准后读取失败")));
                });
    }

    // ---------- 延期拒绝 ----------

    @Transactional
    public DeferralResponse rejectDeferral(String equipmentId, String deferKey,
                                           RejectDeferralRequest req) {
        lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + deferKey + "|" + req.approver() + "|" + req.reason();
        return idempotency.execute(req.requestId(), "REJECT_DEFERRAL", fingerprint,
                DeferralResponse.class, () -> {
                    Deferral deferral = findPendingDeferralForDecision(equipmentId, deferKey);
                    repository.rejectDeferral(deferral.deferralId(), req.approver(), req.reason(),
                            req.requestId(), clock.instant());
                    return toResponse(repository.findDeferral(equipmentId, deferKey)
                            .orElseThrow(() -> new IllegalStateException("延期记录拒绝后读取失败")));
                });
    }

    // ---------- 延期历史 ----------

    @Transactional(readOnly = true)
    public List<DeferralResponse> listDeferrals(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listDeferrals(equipmentId).stream().map(this::toResponse).toList();
    }

    // ---------- 延期内部规则 ----------

    /** 待审批延期或超期封锁时禁止新增运行读数（不影响修订与查询）。 */
    private void checkReadingAllowed(Equipment equipment) {
        repository.findPendingDeferral(equipment.equipmentId()).ifPresent(pending -> {
            throw ApiException.conflict("DEFERRAL_PENDING",
                    "存在待审批延期（" + pending.deferKey() + "），审批完成前不允许新增运行读数");
        });
        int cycleNo = repository.countMaintenances(equipment.equipmentId());
        long approved = repository.sumApprovedDeferMinutes(equipment.equipmentId(), cycleNo);
        long runMinutes = currentRunMinutes(equipment.equipmentId());
        if (isOverdue(equipment.maintenancePeriodMinutes(), approved, runMinutes)) {
            throw ApiException.conflict("EQUIPMENT_OVERDUE",
                    "设备已超期封锁（本轮运行 " + runMinutes + " 分钟，生效阈值 "
                            + (equipment.maintenancePeriodMinutes() + approved) + " 分钟），"
                            + "完成保养前不允许新增运行读数");
        }
    }

    /** 超期封锁判定：本周期有批准延期且运行分钟已达到延期后新阈值。 */
    private boolean isOverdue(long periodMinutes, long approvedDeferralMinutes, long runMinutes) {
        return approvedDeferralMinutes > 0
                && runMinutes >= periodMinutes + approvedDeferralMinutes;
    }

    /** 本轮运行分钟 = 最新读数累计工时 - 最近保养锚点工时（无保养从 0 计）。 */
    private long currentRunMinutes(String equipmentId) {
        long latestCumulative = repository.findLatestReading(equipmentId)
                .map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = repository.findLastMaintenance(equipmentId)
                .map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        return latestCumulative - anchorCumulative;
    }

    /** 延期额度校验：单次不超过周期 25%，本周期累计（含本次）不超过周期 50%，超限 422。 */
    private void checkDeferralLimits(long periodMinutes, long approvedMinutes, long deferMinutes) {
        if (deferMinutes * 4 > periodMinutes) {
            throw ApiException.unprocessable("DEFERRAL_SINGLE_LIMIT",
                    "单次延期 " + deferMinutes + " 分钟超过保养周期 25% 上限：周期 "
                            + periodMinutes + " 分钟，单次最多允许 " + (periodMinutes / 4) + " 分钟");
        }
        if ((approvedMinutes + deferMinutes) * 2 > periodMinutes) {
            throw ApiException.unprocessable("DEFERRAL_CUMULATIVE_LIMIT",
                    "本周期累计延期超限：已累计 " + approvedMinutes + " 分钟，本次申请 "
                            + deferMinutes + " 分钟，允许上限 " + (periodMinutes / 2)
                            + " 分钟（保养周期 " + periodMinutes + " 分钟的 50%）");
        }
    }

    /** 取出待审批延期供批准/拒绝：不存在 404，非待审批（含周期结束失效）409。 */
    private Deferral findPendingDeferralForDecision(String equipmentId, String deferKey) {
        Deferral deferral = repository.findDeferral(equipmentId, deferKey)
                .orElseThrow(() -> ApiException.notFound("DEFERRAL_NOT_FOUND",
                        "延期记录不存在：" + deferKey));
        if (!Deferral.STATUS_PENDING.equals(deferral.status())) {
            throw ApiException.conflict("DEFERRAL_NOT_PENDING",
                    "延期不处于待审批状态（当前 " + deferral.status() + "），不可再审批");
        }
        int cycleNo = repository.countMaintenances(equipmentId);
        if (deferral.cycleNo() != cycleNo) {
            throw ApiException.conflict("DEFERRAL_CYCLE_CLOSED",
                    "延期所属保养周期已结束，不可再审批");
        }
        return deferral;
    }

    private DeferralResponse toResponse(Deferral deferral) {
        return new DeferralResponse(deferral.deferralId(), deferral.equipmentId(),
                deferral.deferKey(), deferral.cycleNo(), deferral.deferMinutes(),
                deferral.reason(), deferral.applicant(), deferral.approver(),
                deferral.rejectReason(), deferral.status(), deferral.appliedCumulativeMinutes(),
                deferral.originalThresholdMinutes(), deferral.newThresholdMinutes(),
                deferral.createdAt(), deferral.decidedAt());
    }
}
