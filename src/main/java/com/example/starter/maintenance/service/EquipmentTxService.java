package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import com.example.starter.maintenance.api.dto.RevisionRequestView;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.api.dto.WitnessReadingRequest;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.domain.RevisionDecision;
import com.example.starter.maintenance.domain.RevisionRequest;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 *
 * <p>大幅读数修订：修订值与当前有效值差额绝对值超出设备阈值时先写入 PENDING 申请（快照阈值、差额、
 * 提交人及提交时有效版本），不覆盖有效读数、不参与保养周期计算；另一名维护人见证同一读数版本后
 * 才更新读数并追加修订历史。见证前读数产生新的生效修订时，旧申请被置为 SUPERSEDED，不可再见证。</p>
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
        String fingerprint = req.equipmentId() + "|" + req.maintenancePeriodMinutes()
                + "|" + req.revisionThresholdMinutes();
        return idempotency.execute(req.requestId(), "REGISTER_EQUIPMENT", fingerprint,
                EquipmentResponse.class, () -> {
                    if (repository.findEquipment(req.equipmentId()).isPresent()) {
                        throw ApiException.conflict("EQUIPMENT_EXISTS", "设备已存在：" + req.equipmentId());
                    }
                    repository.insertEquipment(req.equipmentId(), req.maintenancePeriodMinutes(),
                            req.revisionThresholdMinutes(), clock.instant());
                    return new EquipmentResponse(req.equipmentId(), req.maintenancePeriodMinutes(),
                            req.revisionThresholdMinutes(), 1L);
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
                            req.cumulativeMinutes(), req.requestId(), null, null, now);
                    repository.incrementVersion(equipmentId);
                    return readingResponse(equipmentId,
                            new Reading(equipmentId, req.readingId(), req.sampledAt(),
                                    req.cumulativeMinutes(), 1),
                            false, equipment.version() + 1);
                });
    }

    // ---------- 修订读数 ----------

    @Transactional
    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + readingId + "|" + req.cumulativeMinutes()
                + "|" + req.expectedVersion() + "|" + req.submitterId();
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
                    if (req.cumulativeMinutes() < reading.cumulativeMinutes()) {
                        throw ApiException.unprocessable("READING_BACKWARD",
                                "修订读数不得低于当前有效读数（" + reading.cumulativeMinutes()
                                        + " 分钟），读数倒退不允许");
                    }
                    checkMonotonic(equipmentId, reading.sampledAt(), req.cumulativeMinutes());

                    long delta = RevisionDecision.delta(reading.cumulativeMinutes(), req.cumulativeMinutes());
                    Instant now = clock.instant();

                    if (RevisionDecision.requiresWitness(reading.cumulativeMinutes(),
                            req.cumulativeMinutes(), equipment.revisionThresholdMinutes())) {
                        // 超阈值：旧待确认申请被新修订取代，新申请进入待确认，不覆盖有效读数
                        repository.markPendingRevisionSuperseded(equipmentId, readingId);
                        RevisionRequest request = new RevisionRequest(
                                equipmentId, readingId, req.requestId(),
                                reading.revisionNo(), reading.revisionNo() + 1,
                                reading.cumulativeMinutes(), req.cumulativeMinutes(), delta,
                                equipment.revisionThresholdMinutes(), req.submitterId(), null,
                                RevisionRequest.STATUS_PENDING, equipment.version(), now, null);
                        repository.insertRevisionRequest(request);
                        repository.incrementVersion(equipmentId);
                        return readingResponse(equipmentId, reading,
                                repository.existsMaintenanceAnchoringReading(equipmentId, readingId),
                                equipment.version() + 1, request);
                    }

                    // 阈值内：直接生效；读数既有待确认申请因版本已变化而作废
                    repository.markPendingRevisionSuperseded(equipmentId, readingId);
                    int newRevisionNo = reading.revisionNo() + 1;
                    repository.updateReadingValue(equipmentId, readingId, req.cumulativeMinutes(),
                            newRevisionNo, now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            req.cumulativeMinutes(), req.requestId(), req.submitterId(), null, now);
                    repository.incrementVersion(equipmentId);
                    Reading updated = new Reading(equipmentId, readingId, reading.sampledAt(),
                            req.cumulativeMinutes(), newRevisionNo);
                    return readingResponse(equipmentId, updated, false, equipment.version() + 1);
                });
    }

    // ---------- 见证大幅修订 ----------

    @Transactional
    public ReadingResponse witnessReading(String equipmentId, String readingId, WitnessReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + readingId + "|" + req.baseRevisionNo()
                + "|" + req.expectedVersion() + "|" + req.witnessId();
        return idempotency.execute(req.requestId(), "WITNESS_READING", fingerprint,
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    Reading reading = repository.findReading(equipmentId, readingId)
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "读数不存在：" + readingId));
                    RevisionRequest pending = repository.findPendingRevisionRequest(equipmentId, readingId)
                            .orElseThrow(() -> ApiException.unprocessable("REVISION_NOT_PENDING",
                                    "该读数不存在待确认的大幅修订申请，无法见证"));
                    if (pending.baseRevisionNo() != req.baseRevisionNo()) {
                        throw ApiException.unprocessable("REVISION_VERSION_MISMATCH",
                                "见证指定的读数版本已变化：申请基准版本 " + pending.baseRevisionNo()
                                        + "，请求见证版本 " + req.baseRevisionNo());
                    }
                    if (reading.revisionNo() != pending.baseRevisionNo()) {
                        repository.markPendingRevisionSuperseded(equipmentId, readingId);
                        throw ApiException.unprocessable("REVISION_VERSION_CHANGED",
                                "读数有效版本已变化（当前修订号 " + reading.revisionNo()
                                        + "），旧见证不得批准新读数");
                    }
                    if (req.witnessId().equals(pending.submitterId())) {
                        throw ApiException.unprocessable("WITNESS_SAME_AS_SUBMITTER",
                                "见证人不得与修订提交人为同一维护人：" + req.witnessId());
                    }
                    if (repository.existsMaintenanceAnchoringReading(equipmentId, readingId)) {
                        repository.markPendingRevisionSuperseded(equipmentId, readingId);
                        throw ApiException.conflict("READING_ANCHORED",
                                "读数已作为历史保养锚点，待确认修订作废：" + readingId);
                    }
                    try {
                        checkMonotonic(equipmentId, reading.sampledAt(), pending.proposedCumulativeMinutes());
                    } catch (ApiException e) {
                        repository.markPendingRevisionSuperseded(equipmentId, readingId);
                        throw e;
                    }

                    Instant now = clock.instant();
                    int updated = repository.markRevisionRequestEffective(equipmentId, readingId,
                            pending.requestId(), req.witnessId(), now);
                    if (updated != 1) {
                        throw ApiException.conflict("REVISION_ALREADY_DECIDED",
                                "待确认修订已被其他见证处理或已作废：" + pending.requestId());
                    }
                    int newRevisionNo = pending.proposedRevisionNo();
                    repository.updateReadingValue(equipmentId, readingId,
                            pending.proposedCumulativeMinutes(), newRevisionNo, now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            pending.proposedCumulativeMinutes(), pending.requestId(),
                            pending.submitterId(), req.witnessId(), now);
                    repository.incrementVersion(equipmentId);
                    Reading effective = new Reading(equipmentId, readingId, reading.sampledAt(),
                            pending.proposedCumulativeMinutes(), newRevisionNo);
                    return readingResponse(equipmentId, effective,
                            false, equipment.version() + 1);
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
                    // 以有效值锚定后，该读数的待确认修订永远无法再生效，置为作废
                    repository.markPendingRevisionSuperseded(equipmentId, req.readingId());
                    Instant now = clock.instant();
                    long maintenanceId = repository.insertMaintenance(equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            req.requestId(), now);
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
        // 待确认值不进入 reading 表，最新有效读数天然不含待确认值，周期判定不受其影响
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long runMinutes = latestCumulative - anchorCumulative;
        String status = runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK";
        return new StatusResponse(equipmentId, equipment.version(), equipment.maintenancePeriodMinutes(),
                equipment.revisionThresholdMinutes(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                runMinutes, status);
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        Map<String, RevisionRequest> pendingByReading = repository.listPendingRevisionRequests(equipmentId)
                .stream()
                .collect(Collectors.toMap(RevisionRequest::readingId, Function.identity(),
                        (first, second) -> first));
        return repository.listReadings(equipmentId).stream()
                .map(reading -> readingResponse(equipmentId, reading,
                        repository.existsMaintenanceAnchoringReading(equipmentId, reading.readingId()),
                        equipment.version(), pendingByReading.get(reading.readingId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RevisionView> listRevisions(String equipmentId, String readingId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        repository.findReading(equipmentId, readingId)
                .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND", "读数不存在：" + readingId));
        return repository.listRevisions(equipmentId, readingId).stream()
                .map(row -> new RevisionView(row.revisionNo(), row.cumulativeMinutes(),
                        row.requestId(), row.submitterId(), row.witnessId(), row.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RevisionRequestView> listRevisionRequests(String equipmentId, String readingId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        repository.findReading(equipmentId, readingId)
                .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND", "读数不存在：" + readingId));
        return repository.listRevisionRequests(equipmentId, readingId).stream()
                .map(request -> new RevisionRequestView(request.readingId(), request.requestId(),
                        request.status(), request.baseRevisionNo(), request.proposedRevisionNo(),
                        request.currentCumulativeMinutes(), request.proposedCumulativeMinutes(),
                        request.deltaMinutes(), request.thresholdMinutes(), request.submitterId(),
                        request.witnessId(), request.expectedVersion(), request.createdAt(),
                        request.witnessedAt()))
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

    /** 单调性校验：新值须同时不早于前相邻读数、不晚于后相邻读数（按采样时刻排序，仅比较有效读数）。 */
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

    private ReadingResponse readingResponse(String equipmentId, Reading reading, boolean anchored,
                                            long equipmentVersion) {
        return readingResponse(equipmentId, reading, anchored, equipmentVersion,
                repository.findPendingRevisionRequest(equipmentId, reading.readingId()).orElse(null));
    }

    private ReadingResponse readingResponse(String equipmentId, Reading reading, boolean anchored,
                                            long equipmentVersion, RevisionRequest pending) {
        return new ReadingResponse(
                equipmentId,
                reading.readingId(),
                reading.sampledAt(),
                reading.cumulativeMinutes(),
                reading.revisionNo(),
                anchored,
                equipmentVersion,
                pending == null ? null : pending.requestId(),
                pending == null ? null : pending.proposedCumulativeMinutes(),
                pending == null ? null : pending.deltaMinutes(),
                pending == null ? null : pending.thresholdMinutes(),
                pending == null ? null : pending.submitterId());
    }
}
