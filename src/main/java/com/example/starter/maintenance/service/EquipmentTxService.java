package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddRawReadingRequest;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.RegisterRangeRequest;
import com.example.starter.maintenance.api.dto.ReviseRawReadingRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.service.IdempotencyService.WriteIdentity;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 * 启用量程的设备只接受原始读数（raw + wrapCount），归一累计分钟 = wrapCount * M + raw，
 * 先检查 long 溢出，再接入原前后邻居单调校验；不根据数值下降猜测回绕。
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
    public EquipmentResponse register(RegisterEquipmentRequest req, WriteIdentity identity) {
        return idempotency.execute(req.requestId(), identity.operation(), identity.fingerprint(),
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
    public EquipmentResponse registerRange(String equipmentId, RegisterRangeRequest req,
                                           WriteIdentity identity) {
        Equipment equipment = lockEquipment(equipmentId);
        return idempotency.execute(req.requestId(), identity.operation(), identity.fingerprint(),
                EquipmentResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    if (equipment.rangeM() != null) {
                        throw ApiException.conflict("RANGE_ALREADY_SET",
                                "量程已登记且不可修改：" + equipment.rangeM());
                    }
                    if (repository.countReadings(equipmentId) > 0) {
                        throw ApiException.conflict("RANGE_HAS_READINGS",
                                "设备已存在读数，不可再登记量程");
                    }
                    repository.setRange(equipmentId, req.rangeM());
                    repository.incrementVersion(equipmentId);
                    return new EquipmentResponse(equipmentId, equipment.maintenancePeriodMinutes(),
                            req.rangeM(), equipment.version() + 1);
                });
    }

    // ---------- 新增读数（直接累计分钟，仅未启用量程设备） ----------

    @Transactional
    public ReadingResponse addReading(String equipmentId, AddReadingRequest req, WriteIdentity identity) {
        Equipment equipment = lockEquipment(equipmentId);
        return idempotency.execute(req.requestId(), identity.operation(), identity.fingerprint(),
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    rejectLegacyWriteOnRange(equipment);
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
                                    req.cumulativeMinutes(), 1, null, null),
                            now);
                    repository.insertRevision(equipmentId, req.readingId(), 1,
                            req.cumulativeMinutes(), null, null, req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, req.readingId(), req.sampledAt(),
                            req.cumulativeMinutes(), 1, false, equipment.version() + 1, null, null);
                });
    }

    // ---------- 新增原始读数（raw + wrapCount，仅已启用量程设备） ----------

    @Transactional
    public ReadingResponse addRawReading(String equipmentId, AddRawReadingRequest req,
                                         WriteIdentity identity) {
        Equipment equipment = lockEquipment(equipmentId);
        return idempotency.execute(req.requestId(), identity.operation(), identity.fingerprint(),
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    long rangeM = requireRange(equipment);
                    if (repository.findReading(equipmentId, req.readingId()).isPresent()) {
                        throw ApiException.conflict("READING_EXISTS", "读数已存在：" + req.readingId());
                    }
                    if (repository.findReadingAt(equipmentId, req.sampledAt()).isPresent()) {
                        throw ApiException.unprocessable("READING_TIME_DUPLICATE",
                                "同一设备同一采样时刻仅允许一条读数");
                    }
                    long normalized = normalize(req.raw(), req.wrapCount(), rangeM);
                    checkMonotonic(equipmentId, req.sampledAt(), normalized);
                    Instant now = clock.instant();
                    repository.insertReading(
                            new Reading(equipmentId, req.readingId(), req.sampledAt(),
                                    normalized, 1, req.raw(), req.wrapCount()),
                            now);
                    repository.insertRevision(equipmentId, req.readingId(), 1,
                            normalized, req.raw(), req.wrapCount(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, req.readingId(), req.sampledAt(),
                            normalized, 1, false, equipment.version() + 1, req.raw(), req.wrapCount());
                });
    }

    // ---------- 修订读数（直接累计分钟，仅未启用量程设备） ----------

    @Transactional
    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req,
                                         WriteIdentity identity) {
        Equipment equipment = lockEquipment(equipmentId);
        return idempotency.execute(req.requestId(), identity.operation(), identity.fingerprint(),
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    rejectLegacyWriteOnRange(equipment);
                    Reading reading = findReadingOrThrow(equipmentId, readingId);
                    rejectIfAnchored(equipmentId, readingId);
                    checkMonotonic(equipmentId, reading.sampledAt(), req.cumulativeMinutes());
                    int newRevisionNo = reading.revisionNo() + 1;
                    Instant now = clock.instant();
                    repository.updateReadingValue(equipmentId, readingId, req.cumulativeMinutes(),
                            newRevisionNo, now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            req.cumulativeMinutes(), null, null, req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, readingId, reading.sampledAt(),
                            req.cumulativeMinutes(), newRevisionNo, false, equipment.version() + 1,
                            null, null);
                });
    }

    // ---------- 修订原始读数（raw + wrapCount，仅已启用量程设备） ----------

    @Transactional
    public ReadingResponse reviseRawReading(String equipmentId, String readingId,
                                            ReviseRawReadingRequest req, WriteIdentity identity) {
        Equipment equipment = lockEquipment(equipmentId);
        return idempotency.execute(req.requestId(), identity.operation(), identity.fingerprint(),
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    long rangeM = requireRange(equipment);
                    Reading reading = findReadingOrThrow(equipmentId, readingId);
                    rejectIfAnchored(equipmentId, readingId);
                    if (reading.revisionNo() != req.readingRevision()) {
                        throw ApiException.conflict("READING_REVISION_CONFLICT",
                                "读数修订号冲突：期望 " + req.readingRevision()
                                        + "，当前 " + reading.revisionNo());
                    }
                    long normalized = normalize(req.raw(), req.wrapCount(), rangeM);
                    checkMonotonic(equipmentId, reading.sampledAt(), normalized);
                    int newRevisionNo = reading.revisionNo() + 1;
                    Instant now = clock.instant();
                    repository.updateReadingRaw(equipmentId, readingId, req.raw(), req.wrapCount(),
                            normalized, newRevisionNo, now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            normalized, req.raw(), req.wrapCount(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, readingId, reading.sampledAt(),
                            normalized, newRevisionNo, false, equipment.version() + 1,
                            req.raw(), req.wrapCount());
                });
    }

    // ---------- 完成保养 ----------

    @Transactional
    public MaintenanceResponse completeMaintenance(String equipmentId, CompleteMaintenanceRequest req,
                                                   WriteIdentity identity) {
        Equipment equipment = lockEquipment(equipmentId);
        return idempotency.execute(req.requestId(), identity.operation(), identity.fingerprint(),
                MaintenanceResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    Reading anchor = findReadingOrThrow(equipmentId, req.readingId());
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
                    // 冻结锚点证据：raw、wrapCount、量程 M 及归一值快照，不随后续回绕或修订变化
                    long maintenanceId = repository.insertMaintenance(equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            anchor.raw(), anchor.wrapCount(), equipment.rangeM(),
                            req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            now, equipment.version() + 1,
                            anchor.raw(), anchor.wrapCount(), equipment.rangeM());
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
                        reading.cumulativeMinutes(), reading.revisionNo(),
                        repository.existsMaintenanceAnchoringReading(equipmentId, reading.readingId()),
                        equipment.version(), reading.raw(), reading.wrapCount()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RevisionView> listRevisions(String equipmentId, String readingId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        findReadingOrThrow(equipmentId, readingId);
        return repository.listRevisions(equipmentId, readingId).stream()
                .map(row -> new RevisionView(row.revisionNo(), row.cumulativeMinutes(),
                        row.requestId(), row.createdAt(), row.raw(), row.wrapCount()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listMaintenances(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listMaintenances(equipmentId).stream()
                .map(record -> new MaintenanceResponse(record.maintenanceId(), equipmentId,
                        record.readingId(), record.anchorRevisionNo(), record.anchorSampledAt(),
                        record.anchorCumulativeMinutes(), record.completedAt(), 0L,
                        record.anchorRaw(), record.anchorWrapCount(), record.anchorRangeM()))
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

    private Reading findReadingOrThrow(String equipmentId, String readingId) {
        return repository.findReading(equipmentId, readingId)
                .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND", "读数不存在：" + readingId));
    }

    private void checkVersion(Equipment equipment, long expectedVersion) {
        if (equipment.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "设备版本冲突：期望 " + expectedVersion + "，当前 " + equipment.version());
        }
    }

    /** 已启用量程的设备禁止直接累计分钟写接口，防止绕过原始证据。 */
    private void rejectLegacyWriteOnRange(Equipment equipment) {
        if (equipment.rangeM() != null) {
            throw ApiException.unprocessable("RANGE_ENABLED_REQUIRES_RAW",
                    "设备已启用量程，须使用原始读数接口（raw + wrapCount）");
        }
    }

    /** 原始读数接口要求设备已启用量程。 */
    private long requireRange(Equipment equipment) {
        if (equipment.rangeM() == null) {
            throw ApiException.unprocessable("RANGE_NOT_ENABLED",
                    "设备未启用量程，不可使用原始读数接口");
        }
        return equipment.rangeM();
    }

    private void rejectIfAnchored(String equipmentId, String readingId) {
        if (repository.existsMaintenanceAnchoringReading(equipmentId, readingId)) {
            throw ApiException.conflict("READING_ANCHORED",
                    "读数已作为历史保养锚点，不可修订：" + readingId);
        }
    }

    /**
     * 归一累计分钟 = wrapCount * M + raw。先校验 raw 落在 [0, M)，再以精确算术检查 long 溢出；
     * 非法或溢出均返回 400。
     */
    private long normalize(long raw, long wrapCount, long rangeM) {
        if (raw < 0 || raw >= rangeM) {
            throw ApiException.badRequest("RAW_OUT_OF_RANGE",
                    "raw 须满足 0 <= raw < M（M=" + rangeM + "），实际：" + raw);
        }
        if (wrapCount < 0) {
            throw ApiException.badRequest("WRAP_COUNT_NEGATIVE", "wrapCount 须为非负整数：" + wrapCount);
        }
        try {
            return Math.addExact(Math.multiplyExact(wrapCount, rangeM), raw);
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("NORMALIZED_OVERFLOW",
                    "归一累计分钟（wrapCount * M + raw）超出 long 范围");
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
