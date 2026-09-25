package com.example.starter.maintenance.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.ConversionRecordView;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.domain.ConversionRecord;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.MeterUnit;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 * 多单位支持：存储以设备登记单位为准，单调性、保养锚点与状态判定一律使用换算后整数分钟；
 * 跨单位提交只追加换算留痕，不产生额外读数条目。
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

    /** 登记请求的幂等指纹（原始参数，不含 requestId）；外观层与事务层共用。 */
    public static String registerFingerprint(RegisterEquipmentRequest req) {
        return req.equipmentId() + "|" + req.unit() + "|" + req.maintenancePeriodMinutes()
                + "|" + req.maintenancePeriod();
    }

    @Transactional
    public EquipmentResponse register(RegisterEquipmentRequest req) {
        return idempotency.execute(req.requestId(), "REGISTER_EQUIPMENT", registerFingerprint(req),
                EquipmentResponse.class, () -> {
                    MeterUnit unit = resolveDeclaredUnit(req.unit());
                    BigDecimal periodValue;
                    long periodMinutes;
                    if (unit == MeterUnit.MINUTES) {
                        if (req.maintenancePeriodMinutes() == null) {
                            throw ApiException.badRequest("PERIOD_REQUIRED",
                                    "MINUTES 设备必须提供 maintenancePeriodMinutes（正整数分钟）");
                        }
                        periodMinutes = req.maintenancePeriodMinutes();
                        periodValue = BigDecimal.valueOf(periodMinutes);
                    } else {
                        periodValue = requirePeriodValue(req.maintenancePeriod());
                        periodMinutes = unit.toMinutes(periodValue);
                        if (periodMinutes < 1) {
                            throw ApiException.badRequest("PERIOD_INVALID",
                                    "保养周期换算后不足 1 分钟：" + req.maintenancePeriod());
                        }
                    }
                    if (repository.findEquipment(req.equipmentId()).isPresent()) {
                        throw ApiException.conflict("EQUIPMENT_EXISTS", "设备已存在：" + req.equipmentId());
                    }
                    repository.insertEquipment(req.equipmentId(), unit, periodValue, periodMinutes,
                            clock.instant());
                    return new EquipmentResponse(req.equipmentId(), unit.name(), periodValue,
                            periodMinutes, 1L);
                });
    }

    // ---------- 新增读数 ----------

    @Transactional
    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.readingId() + "|" + req.sampledAt()
                + "|" + req.cumulativeMinutes() + "|" + req.cumulativeValue() + "|" + req.unit()
                + "|" + req.expectedVersion();
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
                    ResolvedValue resolved = resolveValue(equipment, req.unit(),
                            req.cumulativeMinutes(), req.cumulativeValue());
                    Neighbors neighbors = findNeighbors(equipmentId, req.sampledAt());
                    checkMonotonic(neighbors, resolved.minutes());
                    Instant now = clock.instant();
                    repository.insertReading(
                            new Reading(equipmentId, req.readingId(), req.sampledAt(),
                                    resolved.value(), resolved.minutes(), 1),
                            now);
                    repository.insertRevision(equipmentId, req.readingId(), 1,
                            resolved.value(), resolved.minutes(), req.requestId(), now);
                    recordConversion(equipment, req.readingId(), 1, resolved, neighbors,
                            req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, req.readingId(), req.sampledAt(),
                            equipment.unit().name(), resolved.value(), resolved.minutes(),
                            1, false, equipment.version() + 1);
                });
    }

    // ---------- 修订读数 ----------

    @Transactional
    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + readingId + "|" + req.cumulativeMinutes()
                + "|" + req.cumulativeValue() + "|" + req.unit() + "|" + req.expectedVersion();
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
                    ResolvedValue resolved = resolveValue(equipment, req.unit(),
                            req.cumulativeMinutes(), req.cumulativeValue());
                    Neighbors neighbors = findNeighbors(equipmentId, reading.sampledAt());
                    checkMonotonic(neighbors, resolved.minutes());
                    int newRevisionNo = reading.revisionNo() + 1;
                    Instant now = clock.instant();
                    repository.updateReadingValue(equipmentId, readingId, resolved.value(),
                            resolved.minutes(), newRevisionNo, now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            resolved.value(), resolved.minutes(), req.requestId(), now);
                    recordConversion(equipment, readingId, newRevisionNo, resolved, neighbors,
                            req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, readingId, reading.sampledAt(),
                            equipment.unit().name(), resolved.value(), resolved.minutes(),
                            newRevisionNo, false, equipment.version() + 1);
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
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeValue(),
                            anchor.cumulativeMinutes(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), equipment.unit().name(),
                            anchor.cumulativeValue(), anchor.cumulativeMinutes(),
                            now, equipment.version() + 1);
                });
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public EquipmentResponse getEquipment(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return new EquipmentResponse(equipment.equipmentId(), equipment.unit().name(),
                equipment.maintenancePeriodValue(), equipment.maintenancePeriodMinutes(),
                equipment.version());
    }

    @Transactional(readOnly = true)
    public StatusResponse getStatus(String equipmentId, String displayUnitTag) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        MeterUnit displayUnit = displayUnitTag == null ? equipment.unit() : parseUnit(displayUnitTag);
        Optional<Reading> latest = repository.findLatestReading(equipmentId);
        Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long runMinutes = latestCumulative - anchorCumulative;
        String status = runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK";
        return new StatusResponse(equipmentId, equipment.version(), equipment.unit().name(),
                equipment.maintenancePeriodValue(), equipment.maintenancePeriodMinutes(),
                latest.map(Reading::sampledAt).orElse(null),
                latest.map(Reading::cumulativeValue).orElse(BigDecimal.ZERO), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null),
                last.map(MaintenanceRecord::anchorCumulativeValue).orElse(BigDecimal.ZERO),
                anchorCumulative, runMinutes, status,
                displayUnit.name(), displayUnit.displayFromMinutes(runMinutes),
                displayUnit.displayFromMinutes(equipment.maintenancePeriodMinutes()));
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listReadings(equipmentId).stream()
                .map(reading -> new ReadingResponse(equipmentId, reading.readingId(), reading.sampledAt(),
                        equipment.unit().name(), reading.cumulativeValue(), reading.cumulativeMinutes(),
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
                .map(row -> new RevisionView(row.revisionNo(), row.cumulativeValue(),
                        row.cumulativeMinutes(), row.requestId(), row.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listMaintenances(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listMaintenances(equipmentId).stream()
                .map(record -> new MaintenanceResponse(record.maintenanceId(), equipmentId,
                        record.readingId(), record.anchorRevisionNo(), record.anchorSampledAt(),
                        equipment.unit().name(), record.anchorCumulativeValue(),
                        record.anchorCumulativeMinutes(), record.completedAt(), 0L))
                .toList();
    }

    /** 设备全部换算留痕（只读，按换算主键稳定升序）。 */
    @Transactional(readOnly = true)
    public List<ConversionRecordView> listConversions(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listConversions(equipmentId).stream()
                .map(record -> new ConversionRecordView(record.conversionId(), record.equipmentId(),
                        record.readingId(), record.revisionNo(), record.submittedUnit().name(),
                        record.submittedValue(), record.convertedValue(), record.convertedMinutes(),
                        record.equalizedByRounding(), record.requestId(), record.createdAt()))
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

    /** 解析登记单位标签：缺省 MINUTES；非法标签 400。 */
    private MeterUnit resolveDeclaredUnit(String unitTag) {
        if (unitTag == null) {
            return MeterUnit.MINUTES;
        }
        return parseUnit(unitTag);
    }

    private MeterUnit parseUnit(String unitTag) {
        MeterUnit unit = MeterUnit.parse(unitTag);
        if (unit == null) {
            throw ApiException.badRequest("UNIT_INVALID",
                    "非法计量单位：" + unitTag + "（仅支持 MINUTES/HOURS）");
        }
        return unit;
    }

    private BigDecimal requirePeriodValue(BigDecimal value) {
        if (value == null) {
            throw ApiException.badRequest("PERIOD_REQUIRED",
                    "HOURS 设备必须提供 maintenancePeriod（十进制小时，最多 2 位小数）");
        }
        if (value.signum() <= 0 || !MeterUnit.hasAtMostTwoDecimals(value)) {
            throw ApiException.badRequest("PERIOD_INVALID",
                    "保养周期须为正数且最多 2 位小数：" + value);
        }
        return value;
    }

    /**
     * 读数解析结果。
     *
     * @param value          存储值（按设备登记单位）
     * @param minutes        换算后整数分钟（判定口径）
     * @param submittedUnit  提交单位；与设备单位不同（发生换算）时非 null
     * @param submittedValue 提交原始值（按提交单位）；未发生换算时为 null
     * @param exactMinutes   换算前精确分钟数（未四舍五入）；未发生换算时为 null
     */
    private record ResolvedValue(BigDecimal value, long minutes, MeterUnit submittedUnit,
                                 BigDecimal submittedValue, BigDecimal exactMinutes) {
    }

    /**
     * 将请求中的累计工时解析为设备登记单位存储值与判定用整数分钟。
     * 附带单位标签与设备登记单位不同时，要求提供 cumulativeValue 并按 BigDecimal 规则换算。
     */
    private ResolvedValue resolveValue(Equipment equipment, String unitTag,
                                       Long cumulativeMinutes, BigDecimal cumulativeValue) {
        MeterUnit equipmentUnit = equipment.unit();
        MeterUnit submitted = unitTag == null ? equipmentUnit : parseUnit(unitTag);
        if (submitted != equipmentUnit) {
            if (cumulativeValue == null) {
                throw ApiException.badRequest("VALUE_REQUIRED",
                        "提交单位（" + submitted + "）与设备登记单位（" + equipmentUnit
                                + "）不同，必须提供 cumulativeValue");
            }
            if (!MeterUnit.hasAtMostTwoDecimals(cumulativeValue)) {
                throw ApiException.badRequest("VALUE_INVALID",
                        "提交值最多 2 位小数：" + cumulativeValue);
            }
            BigDecimal exact = cumulativeValue.multiply(
                    submitted == MeterUnit.HOURS ? new BigDecimal("60") : BigDecimal.ONE);
            long minutes = submitted.toMinutes(cumulativeValue);
            BigDecimal stored = equipmentUnit == MeterUnit.MINUTES
                    ? BigDecimal.valueOf(minutes)
                    : equipmentUnit.fromMinutes(minutes);
            return new ResolvedValue(stored, minutes, submitted, cumulativeValue, exact);
        }
        // 按设备登记单位解释
        if (equipmentUnit == MeterUnit.MINUTES) {
            long minutes;
            if (cumulativeMinutes != null) {
                minutes = cumulativeMinutes;
            } else if (cumulativeValue != null) {
                if (!MeterUnit.hasAtMostTwoDecimals(cumulativeValue)) {
                    throw ApiException.badRequest("VALUE_INVALID",
                            "提交值最多 2 位小数：" + cumulativeValue);
                }
                minutes = MeterUnit.MINUTES.toMinutes(cumulativeValue);
            } else {
                throw ApiException.badRequest("VALUE_REQUIRED",
                        "MINUTES 设备必须提供 cumulativeMinutes（非负整数分钟）");
            }
            return new ResolvedValue(BigDecimal.valueOf(minutes), minutes, null, null, null);
        }
        // HOURS 设备：按登记单位提交小时值
        if (cumulativeValue == null) {
            throw ApiException.badRequest("VALUE_REQUIRED",
                    "HOURS 设备必须提供 cumulativeValue（十进制小时，最多 2 位小数）");
        }
        if (!MeterUnit.hasAtMostTwoDecimals(cumulativeValue)) {
            throw ApiException.badRequest("VALUE_INVALID",
                    "提交值最多 2 位小数：" + cumulativeValue);
        }
        long minutes = equipmentUnit.toMinutes(cumulativeValue);
        return new ResolvedValue(cumulativeValue, minutes, null, null, null);
    }

    /** 前后相邻读数（按采样时刻）。 */
    private record Neighbors(Optional<Reading> prev, Optional<Reading> next) {
    }

    private Neighbors findNeighbors(String equipmentId, Instant sampledAt) {
        return new Neighbors(repository.findPrevReading(equipmentId, sampledAt),
                repository.findNextReading(equipmentId, sampledAt));
    }

    /** 单调性校验：新值（换算后分钟）须同时不早于前相邻读数、不晚于后相邻读数。 */
    private void checkMonotonic(Neighbors neighbors, long cumulativeMinutes) {
        if (neighbors.prev().isPresent() && cumulativeMinutes < neighbors.prev().get().cumulativeMinutes()) {
            throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                    "累计工时小于前一条读数（" + neighbors.prev().get().cumulativeMinutes()
                            + " 分钟），违反单调不减约束");
        }
        if (neighbors.next().isPresent() && cumulativeMinutes > neighbors.next().get().cumulativeMinutes()) {
            throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                    "累计工时大于后一条读数（" + neighbors.next().get().cumulativeMinutes()
                            + " 分钟），违反单调不减约束");
        }
    }

    /**
     * 跨单位提交时追加换算留痕（只读，不产生额外读数条目）。
     * 换算误差导致与相邻读数相等时视为非递减放行，并标记 equalizedByRounding 以便追溯。
     */
    private void recordConversion(Equipment equipment, String readingId, int revisionNo,
                                  ResolvedValue resolved, Neighbors neighbors,
                                  String requestId, Instant now) {
        if (resolved.submittedUnit() == null) {
            return;
        }
        boolean equalized = equalsNeighborByRounding(resolved, neighbors.prev())
                || equalsNeighborByRounding(resolved, neighbors.next());
        repository.insertConversion(new ConversionRecord(0L, equipment.equipmentId(), readingId,
                revisionNo, resolved.submittedUnit(), resolved.submittedValue(), resolved.value(),
                resolved.minutes(), equalized, requestId, now));
    }

    /** 换算前精确分钟数与相邻读数不等、四舍五入后相等，即为换算误差导致的相等。 */
    private boolean equalsNeighborByRounding(ResolvedValue resolved, Optional<Reading> neighbor) {
        return neighbor.isPresent()
                && resolved.minutes() == neighbor.get().cumulativeMinutes()
                && resolved.exactMinutes().compareTo(
                        BigDecimal.valueOf(neighbor.get().cumulativeMinutes())) != 0;
    }
}
