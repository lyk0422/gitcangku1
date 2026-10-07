package com.example.starter.maintenance.service;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddCycleRequest;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.CycleResponse;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.FatigueCycle;
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
        String fingerprint = req.equipmentId() + "|" + req.maintenancePeriodMinutes()
                + "|" + req.fatigueThreshold() + "|" + req.baselineStressMpa();
        return idempotency.execute(req.requestId(), "REGISTER_EQUIPMENT", fingerprint,
                EquipmentResponse.class, () -> {
                    if (repository.findEquipment(req.equipmentId()).isPresent()) {
                        throw ApiException.conflict("EQUIPMENT_EXISTS", "设备已存在：" + req.equipmentId());
                    }
                    repository.insertEquipment(req.equipmentId(), req.maintenancePeriodMinutes(),
                            req.fatigueThreshold(), req.baselineStressMpa(), clock.instant());
                    return new EquipmentResponse(req.equipmentId(), req.maintenancePeriodMinutes(),
                            req.fatigueThreshold(), req.baselineStressMpa(), 1L);
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
                    if (repository.existsCycleReferencingReading(equipmentId, readingId)) {
                        throw ApiException.conflict("READING_CYCLE_REFERENCED",
                                "读数已被应力循环引用，不可修订：" + readingId);
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
                    if (repository.existsCycleSpanning(equipmentId, anchor.sampledAt())) {
                        throw ApiException.unprocessable("CYCLE_SPANS_ANCHOR",
                                "存在跨越锚点的完整循环，不能把一个损伤拆进两轮");
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

    // ---------- 新增闭合应力循环 ----------

    @Transactional
    public CycleResponse addCycle(String equipmentId, AddCycleRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.cycleId() + "|" + req.fromReadingId() + "|"
                + req.fromRevisionNo() + "|" + req.toReadingId() + "|" + req.toRevisionNo() + "|"
                + req.peakAt() + "|" + req.returnAt() + "|" + req.peakStressMpa() + "|"
                + req.returnStressMpa() + "|" + req.operator() + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "ADD_CYCLE", fingerprint,
                CycleResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    Long baseline = equipment.baselineStressMpa();
                    if (baseline == null) {
                        throw ApiException.unprocessable("FATIGUE_NOT_CONFIGURED",
                                "设备未配置基准应力，不能写入应力循环：" + equipmentId);
                    }
                    if (repository.findCycle(equipmentId, req.cycleId()).isPresent()) {
                        throw ApiException.conflict("CYCLE_EXISTS", "循环已存在：" + req.cycleId());
                    }
                    Reading from = repository.findReading(equipmentId, req.fromReadingId())
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "起始引用读数不存在：" + req.fromReadingId()));
                    Reading to = repository.findReading(equipmentId, req.toReadingId())
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "结束引用读数不存在：" + req.toReadingId()));
                    if (from.revisionNo() != req.fromRevisionNo()
                            || to.revisionNo() != req.toRevisionNo()) {
                        throw ApiException.conflict("READING_REVISION_CONFLICT",
                                "引用读数修订号与当前修订号不一致：起始期望 " + req.fromRevisionNo()
                                        + "（当前 " + from.revisionNo() + "），结束期望 "
                                        + req.toRevisionNo() + "（当前 " + to.revisionNo() + "）");
                    }
                    if (!req.peakAt().isBefore(req.returnAt())
                            || req.peakAt().isBefore(from.sampledAt())
                            || req.returnAt().isAfter(to.sampledAt())) {
                        throw ApiException.unprocessable("CYCLE_OUT_OF_BOUNDS",
                                "循环采样时刻须递增且落在引用读数时刻之间");
                    }
                    if (req.peakStressMpa() <= baseline) {
                        throw ApiException.unprocessable("CYCLE_PEAK_NOT_ABOVE_BASELINE",
                                "峰值应力须大于基准应力（" + baseline + " 兆帕）");
                    }
                    if (req.returnStressMpa() != baseline.longValue()) {
                        throw ApiException.unprocessable("CYCLE_NOT_CLOSED",
                                "回落应力须等于基准应力（" + baseline + " 兆帕），未闭合的半循环不写入");
                    }
                    if (repository.existsOverlappingCycle(equipmentId, req.peakAt(), req.returnAt())) {
                        throw ApiException.unprocessable("CYCLE_OVERLAP",
                                "循环区间与已有循环重叠");
                    }
                    if (repository.existsAnchorStrictlyBetween(equipmentId, req.peakAt(), req.returnAt())) {
                        throw ApiException.unprocessable("CYCLE_SPANS_ANCHOR",
                                "循环区间跨越历史保养锚点，损伤无法归入单一轮次");
                    }
                    long damage = computeDamage(req.peakStressMpa() - baseline);
                    Instant now = clock.instant();
                    repository.insertCycle(new FatigueCycle(equipmentId, req.cycleId(),
                            req.fromReadingId(), req.fromRevisionNo(),
                            req.toReadingId(), req.toRevisionNo(),
                            req.peakAt(), req.returnAt(), req.peakStressMpa(),
                            req.returnStressMpa(), damage, req.operator()), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new CycleResponse(equipmentId, req.cycleId(),
                            req.fromReadingId(), req.fromRevisionNo(),
                            req.toReadingId(), req.toRevisionNo(),
                            req.peakAt(), req.returnAt(), req.peakStressMpa(),
                            req.returnStressMpa(), damage, req.operator(), equipment.version() + 1);
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
        Instant anchorAt = last.map(MaintenanceRecord::anchorSampledAt).orElse(null);
        BigInteger currentDamage = BigInteger.ZERO;
        BigInteger historicalDamage = BigInteger.ZERO;
        for (FatigueCycle cycle : repository.listCycles(equipmentId)) {
            if (anchorAt != null && !cycle.returnAt().isAfter(anchorAt)) {
                historicalDamage = historicalDamage.add(BigInteger.valueOf(cycle.damage()));
            } else {
                currentDamage = currentDamage.add(BigInteger.valueOf(cycle.damage()));
            }
        }
        boolean hoursDue = runMinutes >= equipment.maintenancePeriodMinutes();
        boolean fatigueDue = equipment.fatigueThreshold() != null
                && currentDamage.compareTo(BigInteger.valueOf(equipment.fatigueThreshold())) >= 0;
        String status = (hoursDue || fatigueDue) ? "DUE" : "OK";
        return new StatusResponse(equipmentId, equipment.version(), equipment.maintenancePeriodMinutes(),
                equipment.fatigueThreshold(), equipment.baselineStressMpa(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                runMinutes, clampToLong(currentDamage), clampToLong(historicalDamage), status);
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

    @Transactional(readOnly = true)
    public List<CycleResponse> listCycles(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listCycles(equipmentId).stream()
                .map(cycle -> new CycleResponse(equipmentId, cycle.cycleId(),
                        cycle.fromReadingId(), cycle.fromRevisionNo(),
                        cycle.toReadingId(), cycle.toRevisionNo(),
                        cycle.peakAt(), cycle.returnAt(), cycle.peakStressMpa(),
                        cycle.returnStressMpa(), cycle.damage(), cycle.operator(), equipment.version()))
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

    /** 循环损伤 = 幅值的三次方，精确整数；超出 long 范围拒绝（400）。 */
    private long computeDamage(long amplitude) {
        BigInteger value = BigInteger.valueOf(amplitude).pow(3);
        if (value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw ApiException.badRequest("DAMAGE_OVERFLOW",
                    "循环损伤超出长整型范围，拒绝写入");
        }
        return value.longValue();
    }

    /** 损伤求和上限截断为 long 最大值（达到该值必然已超任何合法阈值）。 */
    private long clampToLong(BigInteger value) {
        return value.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
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
