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
                    checkReadingNotBlocked(equipment);
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
                    // 保养完成：本周期待审批延期即时失效，延期额度随周期归零
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
        long cycleId = last.map(MaintenanceRecord::maintenanceId).orElse(0L);
        long approvedDeferral = repository.sumApprovedDeferralMinutes(equipmentId, cycleId);
        long currentThreshold = equipment.maintenancePeriodMinutes() + approvedDeferral;
        Optional<Deferral> pending = repository.findPendingDeferral(equipmentId);
        boolean overdue = approvedDeferral > 0 && runMinutes >= currentThreshold;
        boolean readingBlocked = pending.isPresent() || overdue;
        String status = overdue ? "OVERDUE"
                : (runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK");
        return new StatusResponse(equipmentId, equipment.version(), equipment.maintenancePeriodMinutes(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                runMinutes, approvedDeferral, currentThreshold,
                pending.map(Deferral::deferKey).orElse(null), readingBlocked, status);
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

    // ---------- 保养延期 ----------

    /**
     * 申请延期：设备须处于 DUE（本轮运行分钟达到保养周期）且保养未完成；
     * 同一设备同时只允许一条待审批延期；单次不超过周期 25%，本周期累计不超过 50%。
     */
    @Transactional
    public DeferralResponse applyDeferral(String equipmentId, ApplyDeferralRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.deferKey() + "|" + req.minutes()
                + "|" + req.reason() + "|" + req.applicant();
        return idempotency.execute(req.requestId(), "APPLY_DEFERRAL", fingerprint,
                DeferralResponse.class, () -> {
                    Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
                    long cycleId = last.map(MaintenanceRecord::maintenanceId).orElse(0L);
                    long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
                    long latestCumulative = repository.findLatestReading(equipmentId)
                            .map(Reading::cumulativeMinutes).orElse(0L);
                    long runMinutes = latestCumulative - anchorCumulative;
                    long period = equipment.maintenancePeriodMinutes();
                    if (runMinutes < period) {
                        throw ApiException.unprocessable("DEFERRAL_NOT_DUE",
                                "设备未达到保养阈值（本轮运行 " + runMinutes + " 分钟，周期 " + period
                                        + " 分钟），不允许申请延期");
                    }
                    if (repository.existsPendingDeferral(equipmentId)) {
                        throw ApiException.conflict("DEFERRAL_PENDING_EXISTS",
                                "已存在待审批延期，同一设备同时只允许一条待审批延期");
                    }
                    if (repository.findDeferral(equipmentId, req.deferKey()).isPresent()) {
                        throw ApiException.conflict("DEFERRAL_KEY_EXISTS",
                                "延期标识已存在：" + req.deferKey());
                    }
                    long singleLimit = period * 25 / 100;
                    if (req.minutes() > singleLimit) {
                        throw ApiException.unprocessable("DEFERRAL_SINGLE_LIMIT",
                                "单次延期 " + req.minutes() + " 分钟超过保养周期的 25%（允许上限 "
                                        + singleLimit + " 分钟）");
                    }
                    long approvedSoFar = repository.sumApprovedDeferralMinutes(equipmentId, cycleId);
                    long cumulativeLimit = period * 50 / 100;
                    if (approvedSoFar + req.minutes() > cumulativeLimit) {
                        throw ApiException.unprocessable("DEFERRAL_CUMULATIVE_LIMIT",
                                "本保养周期累计延期将达 " + (approvedSoFar + req.minutes())
                                        + " 分钟，超过周期的 50%（已累计 " + approvedSoFar
                                        + " 分钟，允许上限 " + cumulativeLimit + " 分钟）");
                    }
                    Instant now = clock.instant();
                    long deferralId = repository.insertDeferral(equipmentId, req.deferKey(), cycleId,
                            req.minutes(), req.reason(), req.applicant(), runMinutes,
                            req.requestId(), now);
                    return new DeferralResponse(deferralId, equipmentId, req.deferKey(), req.minutes(),
                            req.reason(), Deferral.STATUS_PENDING, req.applicant(), null, null,
                            runMinutes, null, null, now, null);
                });
    }

    /**
     * 批准延期：审批人须与申请人不同；批准后本次保养阈值临时后移申请分钟数，
     * 记录固化申请时工时、原阈值、新阈值与双方操作人，不可再变。
     */
    @Transactional
    public DeferralResponse approveDeferral(String equipmentId, String deferKey,
                                            ApproveDeferralRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + deferKey + "|" + req.approver();
        return idempotency.execute(req.requestId(), "APPROVE_DEFERRAL", fingerprint,
                DeferralResponse.class, () -> {
                    Deferral deferral = repository.findDeferral(equipmentId, deferKey)
                            .orElseThrow(() -> ApiException.notFound("DEFERRAL_NOT_FOUND",
                                    "延期申请不存在：" + deferKey));
                    if (!Deferral.STATUS_PENDING.equals(deferral.status())) {
                        throw ApiException.conflict("DEFERRAL_NOT_PENDING",
                                "延期申请当前状态为 " + deferral.status() + "，不可批准");
                    }
                    if (deferral.applicant().equals(req.approver())) {
                        throw ApiException.unprocessable("DEFERRAL_SELF_APPROVE",
                                "审批人不得与申请人相同：" + req.approver());
                    }
                    long approvedSoFar = repository.sumApprovedDeferralMinutes(equipmentId,
                            deferral.cycleMaintenanceId());
                    long originalThreshold = equipment.maintenancePeriodMinutes() + approvedSoFar;
                    long newThreshold = originalThreshold + deferral.requestedMinutes();
                    Instant now = clock.instant();
                    repository.approveDeferral(deferral.deferralId(), req.approver(),
                            originalThreshold, newThreshold, now);
                    return new DeferralResponse(deferral.deferralId(), equipmentId, deferKey,
                            deferral.requestedMinutes(), deferral.reason(), Deferral.STATUS_APPROVED,
                            deferral.applicant(), req.approver(), null, deferral.appliedRunMinutes(),
                            originalThreshold, newThreshold, deferral.createdAt(), now);
                });
    }

    /** 拒绝延期：必须记录理由；拒绝后申请人可重新申请，仍受累计上限约束。 */
    @Transactional
    public DeferralResponse rejectDeferral(String equipmentId, String deferKey,
                                           RejectDeferralRequest req) {
        lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + deferKey + "|" + req.approver() + "|" + req.reason();
        return idempotency.execute(req.requestId(), "REJECT_DEFERRAL", fingerprint,
                DeferralResponse.class, () -> {
                    Deferral deferral = repository.findDeferral(equipmentId, deferKey)
                            .orElseThrow(() -> ApiException.notFound("DEFERRAL_NOT_FOUND",
                                    "延期申请不存在：" + deferKey));
                    if (!Deferral.STATUS_PENDING.equals(deferral.status())) {
                        throw ApiException.conflict("DEFERRAL_NOT_PENDING",
                                "延期申请当前状态为 " + deferral.status() + "，不可拒绝");
                    }
                    Instant now = clock.instant();
                    repository.rejectDeferral(deferral.deferralId(), req.approver(), req.reason(), now);
                    return new DeferralResponse(deferral.deferralId(), equipmentId, deferKey,
                            deferral.requestedMinutes(), deferral.reason(), Deferral.STATUS_REJECTED,
                            deferral.applicant(), req.approver(), req.reason(),
                            deferral.appliedRunMinutes(), null, null, deferral.createdAt(), now);
                });
    }

    /** 延期历史（含待审批/已批准/已拒绝/已失效，按申请先后升序）。 */
    @Transactional(readOnly = true)
    public List<DeferralResponse> listDeferrals(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listDeferrals(equipmentId).stream()
                .map(d -> new DeferralResponse(d.deferralId(), equipmentId, d.deferKey(),
                        d.requestedMinutes(), d.reason(), d.status(), d.applicant(), d.approver(),
                        d.rejectReason(), d.appliedRunMinutes(), d.originalThresholdMinutes(),
                        d.newThresholdMinutes(), d.createdAt(), d.decidedAt()))
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

    /**
     * 新增读数封锁：存在待审批延期时不允许新增读数（409）；已批准延期后本轮运行分钟
     * 达到新阈值且保养未完成时设备进入 OVERDUE，后续读数返回 409。
     * 无延期记录的普通 DUE 不封锁读数（保持既有行为）。
     */
    private void checkReadingNotBlocked(Equipment equipment) {
        String equipmentId = equipment.equipmentId();
        Optional<Deferral> pending = repository.findPendingDeferral(equipmentId);
        if (pending.isPresent()) {
            throw ApiException.conflict("READING_BLOCKED_PENDING_DEFERRAL",
                    "存在待审批延期 " + pending.get().deferKey() + "，审批完成前不允许新增运行读数");
        }
        Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
        long cycleId = last.map(MaintenanceRecord::maintenanceId).orElse(0L);
        long approvedDeferral = repository.sumApprovedDeferralMinutes(equipmentId, cycleId);
        if (approvedDeferral == 0) {
            return;
        }
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long latestCumulative = repository.findLatestReading(equipmentId)
                .map(Reading::cumulativeMinutes).orElse(0L);
        long runMinutes = latestCumulative - anchorCumulative;
        long currentThreshold = equipment.maintenancePeriodMinutes() + approvedDeferral;
        if (runMinutes >= currentThreshold) {
            throw ApiException.conflict("READING_BLOCKED_OVERDUE",
                    "本轮运行 " + runMinutes + " 分钟已达到延期后保养阈值 " + currentThreshold
                            + " 分钟且保养未完成，设备已超期封锁，不允许新增运行读数");
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
