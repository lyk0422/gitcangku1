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
import com.example.starter.maintenance.api.dto.SetMeterRangeRequest;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.MeterNormalization;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 * 已启用量程的设备只接受 raw/wrapCount 原始读数（归一累计 = wrapCount * M + raw），
 * 不根据数值下降猜测回绕；未启用设备保持直接累计值旧契约。
 */
@Service
public class EquipmentTxService {

    private final EquipmentRepository repository;
    private final IdempotencyService idempotency;
    private final RequestFingerprint fingerprints;
    private final Clock clock;

    public EquipmentTxService(EquipmentRepository repository, IdempotencyService idempotency,
                              RequestFingerprint fingerprints, Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.fingerprints = fingerprints;
        this.clock = clock;
    }

    // ---------- 登记设备 ----------

    @Transactional
    public EquipmentResponse register(RegisterEquipmentRequest req) {
        String fingerprint = fingerprints.register(req);
        return idempotency.execute(req.requestId(), "REGISTER_EQUIPMENT", fingerprint,
                EquipmentResponse.class, () -> {
                    if (repository.findEquipment(req.equipmentId()).isPresent()) {
                        throw ApiException.conflict("EQUIPMENT_EXISTS", "设备已存在：" + req.equipmentId());
                    }
                    repository.insertEquipment(req.equipmentId(), req.maintenancePeriodMinutes(), clock.instant());
                    return new EquipmentResponse(req.equipmentId(), req.maintenancePeriodMinutes(), null, 1L);
                });
    }

    // ---------- 登记量程 ----------

    @Transactional
    public EquipmentResponse setMeterRange(String equipmentId, SetMeterRangeRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = fingerprints.setMeterRange(equipmentId, req);
        return idempotency.execute(req.requestId(), "SET_METER_RANGE", fingerprint,
                EquipmentResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    if (equipment.meterRangeMinutes() != null) {
                        throw ApiException.conflict("METER_RANGE_ALREADY_SET",
                                "量程已登记且不可变：" + equipment.meterRangeMinutes());
                    }
                    if (repository.hasReadings(equipmentId)) {
                        throw ApiException.unprocessable("METER_RANGE_READINGS_EXIST",
                                "设备已存在读数，不可再登记量程");
                    }
                    repository.updateMeterRange(equipmentId, req.meterRangeMinutes());
                    repository.incrementVersion(equipmentId);
                    return new EquipmentResponse(equipmentId, equipment.maintenancePeriodMinutes(),
                            req.meterRangeMinutes(), equipment.version() + 1);
                });
    }

    // ---------- 新增读数 ----------

    @Transactional
    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = fingerprints.addReading(equipmentId, req);
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
                    ReadingValue value = resolveNewValue(equipment, req.cumulativeMinutes(),
                            req.raw(), req.wrapCount());
                    checkMonotonic(equipmentId, req.sampledAt(), value.cumulativeMinutes());
                    Instant now = clock.instant();
                    repository.insertReading(
                            new Reading(equipmentId, req.readingId(), req.sampledAt(),
                                    value.cumulativeMinutes(), 1, value.raw(), value.wrapCount()),
                            now);
                    repository.insertRevision(equipmentId, req.readingId(), 1,
                            value.cumulativeMinutes(), value.raw(), value.wrapCount(),
                            req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, req.readingId(), req.sampledAt(),
                            value.cumulativeMinutes(), value.raw(), value.wrapCount(),
                            1, false, equipment.version() + 1);
                });
    }

    // ---------- 修订读数 ----------

    @Transactional
    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = fingerprints.reviseReading(equipmentId, readingId, req);
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
                    if (req.readingRevision() != null && req.readingRevision() != reading.revisionNo()) {
                        throw ApiException.conflict("READING_REVISION_CONFLICT",
                                "读数修订号冲突：期望 " + req.readingRevision()
                                        + "，当前 " + reading.revisionNo());
                    }
                    ReadingValue value = resolveRevisionValue(equipment, req);
                    checkMonotonic(equipmentId, reading.sampledAt(), value.cumulativeMinutes());
                    int newRevisionNo = reading.revisionNo() + 1;
                    Instant now = clock.instant();
                    repository.updateReadingValue(equipmentId, readingId, value.cumulativeMinutes(),
                            value.raw(), value.wrapCount(), newRevisionNo, now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            value.cumulativeMinutes(), value.raw(), value.wrapCount(),
                            req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, readingId, reading.sampledAt(),
                            value.cumulativeMinutes(), value.raw(), value.wrapCount(),
                            newRevisionNo, false, equipment.version() + 1);
                });
    }

    // ---------- 完成保养 ----------

    @Transactional
    public MaintenanceResponse completeMaintenance(String equipmentId, CompleteMaintenanceRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = fingerprints.completeMaintenance(equipmentId, req);
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
                            anchor.raw(), anchor.wrapCount(), equipment.meterRangeMinutes(),
                            req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            anchor.raw(), anchor.wrapCount(), equipment.meterRangeMinutes(),
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
        String status = runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK";
        return new StatusResponse(equipmentId, equipment.version(), equipment.maintenancePeriodMinutes(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                runMinutes, status);
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listReadings(equipmentId).stream()
                .map(reading -> new ReadingResponse(equipmentId, reading.readingId(), reading.sampledAt(),
                        reading.cumulativeMinutes(), reading.raw(), reading.wrapCount(),
                        reading.revisionNo(),
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
                        row.raw(), row.wrapCount(), row.requestId(), row.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listMaintenances(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listMaintenances(equipmentId).stream()
                .map(record -> new MaintenanceResponse(record.maintenanceId(), equipmentId,
                        record.readingId(), record.anchorRevisionNo(), record.anchorSampledAt(),
                        record.anchorCumulativeMinutes(), record.anchorRaw(), record.anchorWrapCount(),
                        record.anchorMeterRangeMinutes(), record.completedAt(), 0L))
                .toList();
    }

    // ---------- 内部规则 ----------

    /** 新读数取值：已启用量程设备只接受 raw/wrapCount，未启用设备只接受直接累计值。 */
    private ReadingValue resolveNewValue(Equipment equipment, Long cumulativeMinutes,
                                         Long raw, Long wrapCount) {
        if (equipment.meterRangeMinutes() != null) {
            if (cumulativeMinutes != null) {
                throw ApiException.unprocessable("CUMULATIVE_INTERFACE_DISABLED",
                        "设备已启用量程，须使用 raw/wrapCount 原始读数，不接受直接累计值");
            }
            return rawValue(equipment.meterRangeMinutes(), raw, wrapCount);
        }
        rejectRawFields(raw, wrapCount);
        if (cumulativeMinutes == null) {
            throw ApiException.badRequest("CUMULATIVE_REQUIRED",
                    "未启用量程设备必须提供 cumulativeMinutes");
        }
        return new ReadingValue(cumulativeMinutes, null, null);
    }

    /** 修订取值：已启用量程设备只能改 raw/wrapCount 且须携带当前修订号。 */
    private ReadingValue resolveRevisionValue(Equipment equipment, ReviseReadingRequest req) {
        if (equipment.meterRangeMinutes() != null) {
            if (req.cumulativeMinutes() != null) {
                throw ApiException.unprocessable("CUMULATIVE_INTERFACE_DISABLED",
                        "设备已启用量程，须使用 raw/wrapCount 原始读数，不接受直接累计值");
            }
            if (req.readingRevision() == null) {
                throw ApiException.badRequest("READING_REVISION_REQUIRED",
                        "原始修订必须携带读数当前修订号 readingRevision");
            }
            return rawValue(equipment.meterRangeMinutes(), req.raw(), req.wrapCount());
        }
        rejectRawFields(req.raw(), req.wrapCount());
        if (req.cumulativeMinutes() == null) {
            throw ApiException.badRequest("CUMULATIVE_REQUIRED",
                    "未启用量程设备必须提供 cumulativeMinutes");
        }
        return new ReadingValue(req.cumulativeMinutes(), null, null);
    }

    private ReadingValue rawValue(long meterRangeMinutes, Long raw, Long wrapCount) {
        if (raw == null || wrapCount == null) {
            throw ApiException.badRequest("RAW_READING_REQUIRED",
                    "已启用量程设备必须同时提供 raw 与 wrapCount");
        }
        return new ReadingValue(MeterNormalization.normalize(meterRangeMinutes, raw, wrapCount),
                raw, wrapCount);
    }

    private void rejectRawFields(Long raw, Long wrapCount) {
        if (raw != null || wrapCount != null) {
            throw ApiException.badRequest("RAW_FIELDS_REQUIRE_METER_RANGE",
                    "设备未启用量程，不接受 raw/wrapCount");
        }
    }

    private record ReadingValue(long cumulativeMinutes, Long raw, Long wrapCount) {
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
