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
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.api.dto.WitnessReadingRequest;
import com.example.starter.maintenance.api.dto.WitnessResponse;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.domain.WitnessRevision;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 * 大幅修订（差额严格大于设备见证阈值）先进入待确认，不覆盖有效读数、不参与保养周期计算，
 * 由另一名维护人见证同一读数版本后才生效。
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
                + "|" + req.witnessThresholdMinutes();
        return idempotency.execute(req.requestId(), "REGISTER_EQUIPMENT", fingerprint,
                EquipmentResponse.class, () -> {
                    if (repository.findEquipment(req.equipmentId()).isPresent()) {
                        throw ApiException.conflict("EQUIPMENT_EXISTS", "设备已存在：" + req.equipmentId());
                    }
                    repository.insertEquipment(req.equipmentId(), req.maintenancePeriodMinutes(),
                            req.witnessThresholdMinutes(), clock.instant());
                    return new EquipmentResponse(req.equipmentId(), req.maintenancePeriodMinutes(),
                            req.witnessThresholdMinutes(), 1L);
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
                            req.cumulativeMinutes(), req.requestId(),
                            null, null, null, null, now);
                    repository.incrementVersion(equipmentId);
                    return effectiveResponse(equipmentId, req.readingId(), req.sampledAt(),
                            req.cumulativeMinutes(), 1, false, null, equipment.version() + 1);
                });
    }

    // ---------- 修订读数 ----------

    @Transactional
    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + readingId + "|" + req.cumulativeMinutes()
                + "|" + req.expectedVersion() + "|" + req.submittedBy();
        return idempotency.execute(req.requestId(), "REVISE_READING", fingerprint,
                ReadingResponse.class,
                () -> doReviseReading(equipmentId, readingId, req, equipment),
                row -> replayRevision(req.requestId(), equipmentId, readingId, equipment, row));
    }

    private ReadingResponse doReviseReading(String equipmentId, String readingId,
                                            ReviseReadingRequest req, Equipment equipment) {
        checkVersion(equipment, req.expectedVersion());
        Reading reading = repository.findReading(equipmentId, readingId)
                .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                        "读数不存在：" + readingId));
        if (repository.existsMaintenanceAnchoringReading(equipmentId, readingId)) {
            throw ApiException.conflict("READING_ANCHORED",
                    "读数已作为历史保养锚点，不可修订：" + readingId);
        }
        if (repository.findPendingWitness(equipmentId, readingId).isPresent()) {
            throw ApiException.unprocessable("READING_PENDING_WITNESS",
                    "该读数存在待确认的大幅修订，须先完成见证或等待其作废后才能再次修订："
                            + readingId);
        }
        checkMonotonic(equipmentId, reading.sampledAt(), req.cumulativeMinutes());

        long base = reading.cumulativeMinutes();
        long difference = req.cumulativeMinutes() - base;
        long threshold = equipment.witnessThresholdMinutes();
        Instant now = clock.instant();

        if (difference > threshold) {
            // 大幅修订：进入待确认，有效读数与修订号均不变，不参与保养周期/阈值计算。
            int pendingRevisionNo = reading.revisionNo() + 1;
            repository.insertWitness(new WitnessRevision(
                            equipmentId, readingId, pendingRevisionNo,
                            req.cumulativeMinutes(), threshold, base, difference,
                            req.requestId(), req.submittedBy(), now),
                    now);
            repository.incrementVersion(equipmentId);
            return new ReadingResponse(equipmentId, readingId, reading.sampledAt(),
                    base, reading.revisionNo(), false,
                    req.cumulativeMinutes(), pendingRevisionNo, difference, threshold,
                    req.submittedBy(), null, equipment.version() + 1);
        }

        // 阈值内修订（含读数持平与向下小幅修正）：即时生效。
        int newRevisionNo = reading.revisionNo() + 1;
        repository.updateReadingValue(equipmentId, readingId, req.cumulativeMinutes(),
                newRevisionNo, now);
        repository.insertRevision(equipmentId, readingId, newRevisionNo,
                req.cumulativeMinutes(), req.requestId(),
                null, null, null, null, now);
        repository.incrementVersion(equipmentId);
        return effectiveResponse(equipmentId, readingId, reading.sampledAt(),
                req.cumulativeMinutes(), newRevisionNo, false, null,
                equipment.version() + 1);
    }

    /**
     * 修订重放按当前状态返回：申请仍待确认时返回待确认视图（有效读数不变、带 pending* 字段）；
     * 已被见证生效时返回生效视图；同键异参由幂等服务先判 409，不会进入本方法。
     */
    private ReadingResponse replayRevision(String requestId, String equipmentId, String readingId,
                                           Equipment equipment,
                                           EquipmentRepository.IdempotencyRow row) {
        Optional<WitnessRevision> pending = repository.findWitnessByRequestId(requestId)
                .filter(w -> w.equipmentId().equals(equipmentId) && w.readingId().equals(readingId));
        if (pending.isPresent()) {
            WitnessRevision w = pending.get();
            Reading reading = repository.findReading(equipmentId, readingId)
                    .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                            "读数不存在：" + readingId));
            return new ReadingResponse(equipmentId, readingId, reading.sampledAt(),
                    reading.cumulativeMinutes(), reading.revisionNo(),
                    repository.existsMaintenanceAnchoringReading(equipmentId, readingId),
                    w.cumulativeMinutes(), w.pendingRevisionNo(), w.differenceMinutes(),
                    w.thresholdMinutes(), w.submittedBy(), null, equipment.version());
        }
        Optional<EquipmentRepository.RevisionRow> effective =
                repository.findRevisionByRequestId(equipmentId, readingId, requestId);
        if (effective.isPresent()) {
            Reading reading = repository.findReading(equipmentId, readingId)
                    .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                            "读数不存在：" + readingId));
            return effectiveResponse(equipmentId, readingId, reading.sampledAt(),
                    reading.cumulativeMinutes(), reading.revisionNo(),
                    repository.existsMaintenanceAnchoringReading(equipmentId, readingId),
                    null, equipment.version());
        }
        return idempotency.parseStoredBody(row, ReadingResponse.class);
    }

    // ---------- 见证大幅修订 ----------

    @Transactional
    public ReadingResponse witnessReading(String equipmentId, String readingId, WitnessReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + readingId + "|" + req.expectedRevisionNo()
                + "|" + req.cumulativeMinutes() + "|" + req.expectedVersion() + "|" + req.witnessedBy();
        return idempotency.execute(req.requestId(), "WITNESS_READING", fingerprint,
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    WitnessRevision pending = repository.findPendingWitness(equipmentId, readingId)
                            .orElseThrow(() -> ApiException.notFound("WITNESS_NOT_FOUND",
                                    "该读数没有待确认的大幅修订：" + readingId));
                    if (pending.submittedBy().equals(req.witnessedBy())) {
                        throw ApiException.unprocessable("WITNESS_SAME_MAINTAINER",
                                "见证人不得与修订提交人为同一人：" + req.witnessedBy());
                    }
                    if (pending.pendingRevisionNo() != req.expectedRevisionNo()) {
                        throw ApiException.unprocessable("WITNESS_VERSION_CHANGED",
                                "待确认修订版本已变化：申请版本 " + pending.pendingRevisionNo()
                                        + "，请求见证版本 " + req.expectedRevisionNo());
                    }
                    if (pending.cumulativeMinutes() != req.cumulativeMinutes()) {
                        throw ApiException.unprocessable("WITNESS_VALUE_MISMATCH",
                                "待确认读数已变化：申请值 " + pending.cumulativeMinutes()
                                        + "，请求见证值 " + req.cumulativeMinutes());
                    }
                    Reading reading = repository.findReading(equipmentId, readingId)
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "读数不存在：" + readingId));
                    if (repository.existsMaintenanceAnchoringReading(equipmentId, readingId)) {
                        throw ApiException.conflict("READING_ANCHORED",
                                "读数已作为历史保养锚点，待确认修订不得再生效：" + readingId);
                    }
                    if (reading.revisionNo() + 1 != pending.pendingRevisionNo()) {
                        throw ApiException.unprocessable("WITNESS_VERSION_CHANGED",
                                "读数已产生新的有效修订，旧见证不得批准新读数：当前有效修订号 "
                                        + reading.revisionNo());
                    }
                    // 见证生效前再次按当前有效读数核对单调性，防止待确认期间相邻读数变化导致倒退。
                    checkMonotonic(equipmentId, reading.sampledAt(), pending.cumulativeMinutes());

                    Instant now = clock.instant();
                    repository.updateReadingValue(equipmentId, readingId, pending.cumulativeMinutes(),
                            pending.pendingRevisionNo(), now);
                    repository.insertRevision(equipmentId, readingId, pending.pendingRevisionNo(),
                            pending.cumulativeMinutes(), pending.requestId(),
                            pending.thresholdMinutes(), pending.differenceMinutes(),
                            pending.submittedBy(), req.witnessedBy(), now);
                    repository.deleteWitness(equipmentId, readingId);
                    repository.incrementVersion(equipmentId);
                    return effectiveResponse(equipmentId, readingId, reading.sampledAt(),
                            pending.cumulativeMinutes(), pending.pendingRevisionNo(), false,
                            null, equipment.version() + 1);
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
                    if (repository.findPendingWitness(equipmentId, req.readingId()).isPresent()) {
                        throw ApiException.unprocessable("READING_PENDING_WITNESS",
                                "锚点读数存在待确认的大幅修订，待确认读数不得触发保养放行："
                                        + req.readingId());
                    }
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
        // reading 表只保存有效读数，待确认值不在其中，故天然不参与本轮运行分钟计算。
        Optional<Reading> latest = repository.findLatestReading(equipmentId);
        Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long runMinutes = latestCumulative - anchorCumulative;
        String status = runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK";
        return new StatusResponse(equipmentId, equipment.version(), equipment.maintenancePeriodMinutes(),
                equipment.witnessThresholdMinutes(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                runMinutes, status);
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        Map<String, WitnessRevision> pendingByReading = repository.listWitnesses(equipmentId).stream()
                .collect(Collectors.toMap(WitnessRevision::readingId, Function.identity()));
        return repository.listReadings(equipmentId).stream()
                .map(reading -> {
                    boolean anchored = repository.existsMaintenanceAnchoringReading(
                            equipmentId, reading.readingId());
                    WitnessRevision pending = pendingByReading.get(reading.readingId());
                    return new ReadingResponse(equipmentId, reading.readingId(), reading.sampledAt(),
                            reading.cumulativeMinutes(), reading.revisionNo(), anchored,
                            pending == null ? null : pending.cumulativeMinutes(),
                            pending == null ? null : pending.pendingRevisionNo(),
                            pending == null ? null : pending.differenceMinutes(),
                            pending == null ? null : pending.thresholdMinutes(),
                            pending == null ? null : pending.submittedBy(),
                            null,
                            equipment.version());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RevisionView> listRevisions(String equipmentId, String readingId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        repository.findReading(equipmentId, readingId)
                .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND", "读数不存在：" + readingId));
        return repository.listRevisions(equipmentId, readingId).stream()
                .map(row -> new RevisionView(row.revisionNo(), row.cumulativeMinutes(),
                        row.requestId(), row.thresholdMinutes(), row.differenceMinutes(),
                        row.submittedBy(), row.witnessedBy(), row.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WitnessResponse> listWitnesses(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listWitnesses(equipmentId).stream()
                .map(witness -> toWitnessResponse(witness, equipment.version()))
                .toList();
    }

    @Transactional(readOnly = true)
    public WitnessResponse getWitness(String equipmentId, String readingId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        WitnessRevision witness = repository.findPendingWitness(equipmentId, readingId)
                .orElseThrow(() -> ApiException.notFound("WITNESS_NOT_FOUND",
                        "该读数没有待确认的大幅修订：" + readingId));
        return toWitnessResponse(witness, equipment.version());
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

    private WitnessResponse toWitnessResponse(WitnessRevision witness, long equipmentVersion) {
        return new WitnessResponse(witness.equipmentId(), witness.readingId(),
                witness.pendingRevisionNo(), witness.baseCumulativeMinutes(),
                witness.cumulativeMinutes(), witness.differenceMinutes(), witness.thresholdMinutes(),
                witness.requestId(), witness.submittedBy(), witness.submittedAt(), equipmentVersion);
    }

    private ReadingResponse effectiveResponse(String equipmentId, String readingId, Instant sampledAt,
                                              long cumulativeMinutes, int revisionNo, boolean anchored,
                                              WitnessRevision pending, long equipmentVersion) {
        return new ReadingResponse(equipmentId, readingId, sampledAt, cumulativeMinutes, revisionNo,
                anchored,
                pending == null ? null : pending.cumulativeMinutes(),
                pending == null ? null : pending.pendingRevisionNo(),
                pending == null ? null : pending.differenceMinutes(),
                pending == null ? null : pending.thresholdMinutes(),
                pending == null ? null : pending.submittedBy(),
                null,
                equipmentVersion);
    }

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

    /** 单调性校验：新值须同时不早于前相邻有效读数、不晚于后相邻有效读数（按采样时刻排序）。 */
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
