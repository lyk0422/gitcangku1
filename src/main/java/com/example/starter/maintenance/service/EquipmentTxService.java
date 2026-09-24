package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CancelDowntimeRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.DowntimeResponse;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterDowntimeRequest;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.domain.DowntimeRecord;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 * 停机扣减量不持久化，始终按当前读数实时计算，因此读数新增/修订后状态即时重算。
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

    // ---------- 登记停机 ----------

    @Transactional
    public DowntimeResponse registerDowntime(String equipmentId, RegisterDowntimeRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.downtimeKey() + "|" + req.startAt() + "|"
                + req.endAt() + "|" + req.reason() + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "REGISTER_DOWNTIME", fingerprint,
                DowntimeResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    if (repository.findDowntime(req.downtimeKey()).isPresent()) {
                        throw ApiException.conflict("DOWNTIME_KEY_EXISTS",
                                "停机区间标识已存在：" + req.downtimeKey());
                    }
                    validateDowntimeRange(equipmentId, req.startAt(), req.endAt());
                    Instant now = clock.instant();
                    repository.insertDowntime(new DowntimeRecord(req.downtimeKey(), equipmentId,
                            req.startAt(), req.endAt(), req.reason(), DowntimeRecord.ACTIVE,
                            req.requestId(), now, null, null), now);
                    repository.incrementVersion(equipmentId);
                    long deduction = deductionOf(equipmentId, req.startAt(), req.endAt());
                    Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
                    boolean included = last.isEmpty()
                            || !req.startAt().isBefore(last.get().anchorSampledAt());
                    return new DowntimeResponse(req.downtimeKey(), equipmentId, req.startAt(),
                            req.endAt(), req.reason(), DowntimeRecord.ACTIVE, deduction, included,
                            now, null, equipment.version() + 1);
                });
    }

    // ---------- 撤销停机 ----------

    @Transactional
    public DowntimeResponse cancelDowntime(String equipmentId, String downtimeKey, CancelDowntimeRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + downtimeKey + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "CANCEL_DOWNTIME", fingerprint,
                DowntimeResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    DowntimeRecord downtime = loadDowntimeOfEquipment(equipmentId, downtimeKey);
                    if (DowntimeRecord.CANCELLED.equals(downtime.status())) {
                        throw ApiException.conflict("DOWNTIME_ALREADY_CANCELLED",
                                "停机区间已撤销，不可重复撤销：" + downtimeKey);
                    }
                    Instant now = clock.instant();
                    int updated = repository.cancelDowntime(downtimeKey, req.requestId(), now);
                    if (updated == 0) {
                        // 并发下已被其他请求撤销（同设备行锁内理论不可达，防御性处理）
                        throw ApiException.conflict("DOWNTIME_ALREADY_CANCELLED",
                                "停机区间已撤销，不可重复撤销：" + downtimeKey);
                    }
                    repository.incrementVersion(equipmentId);
                    return new DowntimeResponse(downtimeKey, equipmentId, downtime.startAt(),
                            downtime.endAt(), downtime.reason(), DowntimeRecord.CANCELLED, 0L,
                            false, downtime.createdAt(), now, equipment.version() + 1);
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
                    // 按上一锚点（无则设备起始）之后、且不晚于本次锚点的生效停机区间结算并固化
                    Instant cycleStart = last.map(MaintenanceRecord::anchorSampledAt).orElse(null);
                    long settledDeduction = cycleDeduction(equipmentId, cycleStart, anchor.sampledAt());
                    long previousAnchorCumulative = last
                            .map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
                    long settledRun = Math.max(0,
                            anchor.cumulativeMinutes() - previousAnchorCumulative - settledDeduction);
                    Instant now = clock.instant();
                    long maintenanceId = repository.insertMaintenance(equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            settledDeduction, settledRun, req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            settledDeduction, settledRun, now, equipment.version() + 1);
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
        Instant anchorTime = last.map(MaintenanceRecord::anchorSampledAt).orElse(null);
        Instant latestTime = latest.map(Reading::sampledAt).orElse(null);
        long deduction = cycleDeduction(equipmentId, anchorTime, latestTime);
        long runMinutes = Math.max(0, latestCumulative - anchorCumulative - deduction);
        String status = runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK";
        return new StatusResponse(equipmentId, equipment.version(), equipment.maintenancePeriodMinutes(),
                latestTime, latestCumulative, anchorTime, anchorCumulative, deduction,
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
                        record.anchorCumulativeMinutes(), record.settledDeductionMinutes(),
                        record.settledRunMinutes(), record.completedAt(), 0L))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DowntimeResponse> listDowntimes(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        Instant anchorTime = repository.findLastMaintenance(equipmentId)
                .map(MaintenanceRecord::anchorSampledAt).orElse(null);
        return repository.listDowntimes(equipmentId).stream()
                .map(downtime -> toDowntimeResponse(equipmentId, downtime, anchorTime, equipment.version()))
                .toList();
    }

    @Transactional(readOnly = true)
    public DowntimeResponse getDowntime(String equipmentId, String downtimeKey) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        DowntimeRecord downtime = loadDowntimeOfEquipment(equipmentId, downtimeKey);
        Instant anchorTime = repository.findLastMaintenance(equipmentId)
                .map(MaintenanceRecord::anchorSampledAt).orElse(null);
        return toDowntimeResponse(equipmentId, downtime, anchorTime, equipment.version());
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
     * 停机区间登记规则（任一违反返回 422）：结束严格晚于开始；起止落在该设备最早/最晚读数采样时刻
     * （含端点）之间；与生效区间不得重叠（仅端点相接合法）；不得跨越任何已有保养锚点时刻。
     */
    private void validateDowntimeRange(String equipmentId, Instant startAt, Instant endAt) {
        if (!endAt.isAfter(startAt)) {
            throw ApiException.unprocessable("DOWNTIME_RANGE_INVALID",
                    "停机结束时刻必须严格晚于开始时刻");
        }
        Optional<Reading> earliest = repository.findEarliestReading(equipmentId);
        Optional<Reading> latest = repository.findLatestReading(equipmentId);
        if (earliest.isEmpty() || latest.isEmpty()) {
            throw ApiException.unprocessable("DOWNTIME_OUTSIDE_READINGS",
                    "设备尚无读数，停机区间起止须落在最早与最晚读数采样时刻之间");
        }
        Instant earliestTime = earliest.get().sampledAt();
        Instant latestTime = latest.get().sampledAt();
        if (startAt.isBefore(earliestTime) || endAt.isAfter(latestTime)) {
            throw ApiException.unprocessable("DOWNTIME_OUTSIDE_READINGS",
                    "停机区间起止须落在最早读数（" + earliestTime + "）与最晚读数（" + latestTime
                            + "）采样时刻之间");
        }
        if (repository.existsActiveOverlap(equipmentId, startAt, endAt)) {
            throw ApiException.unprocessable("DOWNTIME_OVERLAP",
                    "同一设备的生效停机区间不得相互重叠（仅端点相接合法）");
        }
        if (repository.existsMaintenanceAnchorBetween(equipmentId, startAt, endAt)) {
            throw ApiException.unprocessable("DOWNTIME_CROSSES_ANCHOR",
                    "停机区间不得跨越已有保养锚点时刻（锚点恰为区间终点时合法）");
        }
    }

    private DowntimeRecord loadDowntimeOfEquipment(String equipmentId, String downtimeKey) {
        DowntimeRecord downtime = repository.findDowntime(downtimeKey)
                .orElseThrow(() -> ApiException.notFound("DOWNTIME_NOT_FOUND",
                        "停机区间不存在：" + downtimeKey));
        if (!downtime.equipmentId().equals(equipmentId)) {
            throw ApiException.notFound("DOWNTIME_NOT_FOUND", "停机区间不存在：" + downtimeKey);
        }
        return downtime;
    }

    /**
     * 区间扣减量 = 采样时刻不晚于结束时刻的最近读数累计分钟 − 不晚于开始时刻的最近读数累计分钟；
     * 任一边界取不到读数按 0；读数单调不减，结果不为负。
     */
    private long deductionOf(String equipmentId, Instant startAt, Instant endAt) {
        long atEnd = repository.findLatestReadingAtOrBefore(equipmentId, endAt)
                .map(Reading::cumulativeMinutes).orElse(0L);
        long atStart = repository.findLatestReadingAtOrBefore(equipmentId, startAt)
                .map(Reading::cumulativeMinutes).orElse(0L);
        return Math.max(0L, atEnd - atStart);
    }

    /**
     * 某保养周期内扣减合计：生效、开始时刻不早于周期起点（cycleStart 为 null 表示设备起始）、
     * 结束时刻不晚于周期终点（cycleEnd 为 null 表示取最新读数之后）的全部停机区间扣减量之和。
     */
    private long cycleDeduction(String equipmentId, Instant cycleStart, Instant cycleEnd) {
        long total = 0L;
        for (DowntimeRecord downtime : repository.listActiveDowntimes(equipmentId)) {
            boolean afterCycleStart = cycleStart == null || !downtime.startAt().isBefore(cycleStart);
            boolean beforeCycleEnd = cycleEnd == null || !downtime.endAt().isAfter(cycleEnd);
            if (afterCycleStart && beforeCycleEnd) {
                total += deductionOf(equipmentId, downtime.startAt(), downtime.endAt());
            }
        }
        return total;
    }

    private DowntimeResponse toDowntimeResponse(String equipmentId, DowntimeRecord downtime,
                                                Instant anchorTime, long equipmentVersion) {
        boolean active = DowntimeRecord.ACTIVE.equals(downtime.status());
        long deduction = active ? deductionOf(equipmentId, downtime.startAt(), downtime.endAt()) : 0L;
        boolean included = active && (anchorTime == null || !downtime.startAt().isBefore(anchorTime));
        return new DowntimeResponse(downtime.downtimeKey(), equipmentId, downtime.startAt(),
                downtime.endAt(), downtime.reason(), downtime.status(), deduction, included,
                downtime.createdAt(), downtime.revokedAt(), equipmentVersion);
    }
}
