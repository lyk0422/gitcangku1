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
import com.example.starter.maintenance.api.dto.DowntimeResponse;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterDowntimeRequest;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.RevokeDowntimeRequest;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.domain.Downtime;
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
                    recomputeDowntimeDeductions(equipmentId);
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
                    recomputeDowntimeDeductions(equipmentId);
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
                    // 按新锚点结算：固化当时运行分钟与该锚点之后生效停机区间的扣减合计
                    long deductionTotal = deductionTotalAfter(equipmentId, anchor.sampledAt());
                    long latestCumulative = repository.findLatestReading(equipmentId)
                            .map(Reading::cumulativeMinutes).orElse(0L);
                    long runMinutes = Math.max(0L,
                            latestCumulative - anchor.cumulativeMinutes() - deductionTotal);
                    Instant now = clock.instant();
                    long maintenanceId = repository.insertMaintenance(equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            runMinutes, deductionTotal, req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            runMinutes, deductionTotal, now, equipment.version() + 1);
                });
    }

    // ---------- 登记停机区间 ----------

    @Transactional
    public DowntimeResponse registerDowntime(String equipmentId, RegisterDowntimeRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.downtimeKey() + "|" + req.startAt()
                + "|" + req.endAt() + "|" + req.reason() + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "REGISTER_DOWNTIME", fingerprint,
                DowntimeResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    if (repository.findDowntime(req.downtimeKey()).isPresent()) {
                        throw ApiException.conflict("DOWNTIME_EXISTS",
                                "停机区间标识已存在：" + req.downtimeKey());
                    }
                    checkDowntimeRules(equipmentId, req.startAt(), req.endAt());
                    Instant now = clock.instant();
                    long deduction = computeDeduction(equipmentId, req.startAt(), req.endAt());
                    Downtime downtime = new Downtime(req.downtimeKey(), equipmentId,
                            req.startAt(), req.endAt(), req.reason(), deduction,
                            Downtime.STATUS_ACTIVE, now, null);
                    repository.insertDowntime(downtime, req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return toDowntimeResponse(downtime, equipment.version() + 1);
                });
    }

    // ---------- 撤销停机区间 ----------

    @Transactional
    public DowntimeResponse revokeDowntime(String equipmentId, String downtimeKey,
                                           RevokeDowntimeRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + downtimeKey + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "REVOKE_DOWNTIME", fingerprint,
                DowntimeResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    Downtime downtime = repository.findDowntime(downtimeKey)
                            .filter(d -> d.equipmentId().equals(equipmentId))
                            .orElseThrow(() -> ApiException.notFound("DOWNTIME_NOT_FOUND",
                                    "停机区间不存在：" + downtimeKey));
                    if (Downtime.STATUS_REVOKED.equals(downtime.status())) {
                        throw ApiException.conflict("DOWNTIME_ALREADY_REVOKED",
                                "停机区间已撤销，不可重复撤销：" + downtimeKey);
                    }
                    Instant now = clock.instant();
                    repository.revokeDowntime(downtimeKey, now);
                    repository.incrementVersion(equipmentId);
                    Downtime revoked = new Downtime(downtime.downtimeKey(), downtime.equipmentId(),
                            downtime.startAt(), downtime.endAt(), downtime.reason(),
                            downtime.deductionMinutes(), Downtime.STATUS_REVOKED,
                            downtime.createdAt(), now);
                    return toDowntimeResponse(revoked, equipment.version() + 1);
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
        long deductionTotal = deductionTotalAfter(equipmentId,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null));
        long runMinutes = Math.max(0L, latestCumulative - anchorCumulative - deductionTotal);
        String status = runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK";
        return new StatusResponse(equipmentId, equipment.version(), equipment.maintenancePeriodMinutes(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                deductionTotal, runMinutes, status);
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
                        record.anchorCumulativeMinutes(), record.runMinutes(),
                        record.downtimeDeductionMinutes(), record.completedAt(), 0L))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DowntimeResponse> listDowntimes(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listDowntimes(equipmentId).stream()
                .map(downtime -> toDowntimeResponse(downtime, equipment.version()))
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

    /**
     * 停机区间登记规则：结束晚于开始；起止落在最早与最晚读数采样时刻之间（端点含）；
     * 不跨越任何已有保养锚点时刻；与同设备生效区间不重叠（仅端点相接合法）。
     */
    private void checkDowntimeRules(String equipmentId, Instant startAt, Instant endAt) {
        if (!endAt.isAfter(startAt)) {
            throw ApiException.unprocessable("DOWNTIME_TIME_INVALID",
                    "停机结束时刻必须晚于开始时刻");
        }
        Optional<Reading> earliest = repository.findEarliestReading(equipmentId);
        Optional<Reading> latest = repository.findLatestReading(equipmentId);
        if (earliest.isEmpty() || startAt.isBefore(earliest.get().sampledAt())
                || endAt.isAfter(latest.get().sampledAt())) {
            throw ApiException.unprocessable("DOWNTIME_OUT_OF_RANGE",
                    "停机起止时刻须落在设备最早与最晚读数采样时刻之间");
        }
        for (MaintenanceRecord record : repository.listMaintenances(equipmentId)) {
            Instant anchorTime = record.anchorSampledAt();
            if (startAt.isBefore(anchorTime) && anchorTime.isBefore(endAt)) {
                throw ApiException.unprocessable("DOWNTIME_CROSSES_ANCHOR",
                        "停机区间不得跨越已有保养锚点时刻：" + anchorTime);
            }
        }
        for (Downtime active : repository.listActiveDowntimes(equipmentId)) {
            if (active.startAt().isBefore(endAt) && startAt.isBefore(active.endAt())) {
                throw ApiException.unprocessable("DOWNTIME_OVERLAP",
                        "停机区间与生效区间重叠：" + active.downtimeKey());
            }
        }
    }

    /**
     * 区间扣减量：采样时刻不晚于结束时刻的最近读数累计分钟，
     * 减去不晚于开始时刻的最近读数累计分钟；任一侧取不到读数时该侧按 0 计。
     */
    private long computeDeduction(String equipmentId, Instant startAt, Instant endAt) {
        long endCumulative = repository.findReadingAtOrBefore(equipmentId, endAt)
                .map(Reading::cumulativeMinutes).orElse(0L);
        long startCumulative = repository.findReadingAtOrBefore(equipmentId, startAt)
                .map(Reading::cumulativeMinutes).orElse(0L);
        return endCumulative - startCumulative;
    }

    /** 读数新增/修订后重算该设备全部生效停机区间的扣减量（已撤销区间冻结不改写）。 */
    private void recomputeDowntimeDeductions(String equipmentId) {
        for (Downtime active : repository.listActiveDowntimes(equipmentId)) {
            long deduction = computeDeduction(equipmentId, active.startAt(), active.endAt());
            if (deduction != active.deductionMinutes()) {
                repository.updateDowntimeDeduction(active.downtimeKey(), deduction);
            }
        }
    }

    /**
     * 指定锚点时刻之后全部生效停机区间的扣减量之和；anchorTime 为 null 表示尚无保养，
     * 统计全部生效区间。区间不得跨越锚点，故开始时刻不早于锚点即整体位于锚点之后。
     */
    private long deductionTotalAfter(String equipmentId, Instant anchorTime) {
        return repository.listActiveDowntimes(equipmentId).stream()
                .filter(d -> anchorTime == null || !d.startAt().isBefore(anchorTime))
                .mapToLong(Downtime::deductionMinutes)
                .sum();
    }

    private DowntimeResponse toDowntimeResponse(Downtime downtime, long equipmentVersion) {
        return new DowntimeResponse(downtime.downtimeKey(), downtime.equipmentId(),
                downtime.startAt(), downtime.endAt(), downtime.reason(),
                downtime.deductionMinutes(), downtime.status(), downtime.createdAt(),
                downtime.revokedAt(), equipmentVersion);
    }
}
