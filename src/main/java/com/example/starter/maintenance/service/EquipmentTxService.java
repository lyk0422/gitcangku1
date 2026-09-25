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
import com.example.starter.maintenance.api.dto.ConversionView;
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
import com.example.starter.maintenance.domain.MeasurementUnit;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.domain.UnitConverter;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 *
 * <p>多计量单位口径：所有读数与保养周期以设备登记单位十进制存储，同时冗余换算分钟数
 * （HOURS × 60，BigDecimal 精确计算，HALF_UP 四舍五入到最近整数分钟）；单调性、保养锚点
 * 与 DUE/OK 判定统一使用换算后的分钟口径。提交单位与登记单位不同时服务端换算并只读留痕。</p>
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
        ResolvedPeriod period = resolvePeriod(req);
        String fingerprint = registerFingerprint(req.equipmentId(), period);
        return idempotency.execute(req.requestId(), "REGISTER_EQUIPMENT", fingerprint,
                EquipmentResponse.class, () -> {
                    if (repository.findEquipment(req.equipmentId()).isPresent()) {
                        throw ApiException.conflict("EQUIPMENT_EXISTS", "设备已存在：" + req.equipmentId());
                    }
                    Equipment equipment = new Equipment(req.equipmentId(), period.unit(), period.value(),
                            period.minutes(), 1L);
                    repository.insertEquipment(equipment, clock.instant());
                    return new EquipmentResponse(req.equipmentId(), period.unit().name(), period.value(),
                            period.minutes(), 1L);
                });
    }

    // ---------- 新增读数 ----------

    @Transactional
    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        ResolvedValue value = resolveReadingValue(equipment, req.unit(),
                req.cumulativeValue(), req.cumulativeMinutes());
        String fingerprint = equipmentId + "|" + req.readingId() + "|" + req.sampledAt()
                + "|" + value.sourceUnit() + "|" + value.sourceValue() + "|" + req.expectedVersion();
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
                    checkMonotonic(equipmentId, req.sampledAt(), value.minutes());
                    Instant now = clock.instant();
                    repository.insertReading(
                            new Reading(equipmentId, req.readingId(), req.sampledAt(),
                                    value.equipmentValue(), value.minutes(), 1),
                            now);
                    repository.insertRevision(equipmentId, req.readingId(), 1,
                            value.equipmentValue(), value.minutes(), req.requestId(), now);
                    recordConversionIfNeeded(equipment, req.readingId(), 1, value,
                            req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, req.readingId(), req.sampledAt(),
                            value.equipmentValue(), value.minutes(), 1, false, equipment.version() + 1);
                });
    }

    // ---------- 修订读数 ----------

    @Transactional
    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        ResolvedValue value = resolveReadingValue(equipment, req.unit(),
                req.cumulativeValue(), req.cumulativeMinutes());
        String fingerprint = equipmentId + "|" + readingId + "|" + value.sourceUnit() + "|"
                + value.sourceValue() + "|" + req.expectedVersion();
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
                    checkMonotonic(equipmentId, reading.sampledAt(), value.minutes());
                    int newRevisionNo = reading.revisionNo() + 1;
                    Instant now = clock.instant();
                    repository.updateReadingValue(equipmentId, readingId, value.equipmentValue(),
                            value.minutes(), newRevisionNo, now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            value.equipmentValue(), value.minutes(), req.requestId(), now);
                    recordConversionIfNeeded(equipment, readingId, newRevisionNo, value,
                            req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, readingId, reading.sampledAt(),
                            value.equipmentValue(), value.minutes(), newRevisionNo, false,
                            equipment.version() + 1);
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
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeValue(),
                            anchor.cumulativeMinutes(), now, equipment.version() + 1);
                });
    }

    // ---------- 查询 ----------

    /** 设备单位配置（只读）：登记单位、保养周期与换算分钟数。 */
    @Transactional(readOnly = true)
    public EquipmentResponse getEquipment(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return new EquipmentResponse(equipmentId, equipment.measurementUnit().name(),
                equipment.maintenancePeriodValue(), equipment.maintenancePeriodMinutes(),
                equipment.version());
    }

    @Transactional(readOnly = true)
    public StatusResponse getStatus(String equipmentId, String displayUnitName) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        MeasurementUnit displayUnit = parseDisplayUnit(displayUnitName, equipment);
        Optional<Reading> latest = repository.findLatestReading(equipmentId);
        Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
        long latestMinutes = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorMinutes = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        // 判定统一口径：本轮运行分钟由换算分钟数直接相减
        long runMinutes = latestMinutes - anchorMinutes;
        String status = runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK";
        // 登记单位运行工时与展示值均由换算分钟数一次性换算，避免展示两次转换误差累积
        BigDecimal runValue = UnitConverter.displayFromMinutes(runMinutes, equipment.measurementUnit());
        BigDecimal displayRunValue = UnitConverter.displayFromMinutes(runMinutes, displayUnit);
        BigDecimal displayPeriodValue = UnitConverter.displayFromMinutes(
                equipment.maintenancePeriodMinutes(), displayUnit);
        return new StatusResponse(equipmentId, equipment.version(), equipment.measurementUnit().name(),
                equipment.maintenancePeriodValue(), equipment.maintenancePeriodMinutes(),
                latest.map(Reading::sampledAt).orElse(null),
                latest.map(Reading::cumulativeValue).orElse(BigDecimal.ZERO.setScale(2)),
                latestMinutes,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null),
                last.map(MaintenanceRecord::anchorCumulativeValue).orElse(BigDecimal.ZERO.setScale(2)),
                anchorMinutes, runValue, runMinutes, status, displayUnit.name(),
                displayRunValue, displayPeriodValue);
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listReadings(equipmentId).stream()
                .map(reading -> new ReadingResponse(equipmentId, reading.readingId(), reading.sampledAt(),
                        reading.cumulativeValue(), reading.cumulativeMinutes(), reading.revisionNo(),
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
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listMaintenances(equipmentId).stream()
                .map(record -> new MaintenanceResponse(record.maintenanceId(), equipmentId,
                        record.readingId(), record.anchorRevisionNo(), record.anchorSampledAt(),
                        record.anchorCumulativeValue(), record.anchorCumulativeMinutes(),
                        record.completedAt(), 0L))
                .toList();
    }

    /** 换算留痕查询（只读，稳定排序）。 */
    @Transactional(readOnly = true)
    public List<ConversionView> listConversions(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listConversions(equipmentId).stream()
                .map(record -> new ConversionView(record.conversionId(), record.equipmentId(),
                        record.readingId(), record.revisionNo(), record.sourceUnit().name(),
                        record.sourceValue(), record.convertedValue(), record.convertedMinutes(),
                        record.requestId(), record.createdAt()))
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

    /** 单调性校验（统一分钟口径）：换算误差导致相等视为非递减，不抛 422。 */
    private void checkMonotonic(String equipmentId, Instant sampledAt, long cumulativeMinutes) {
        Optional<Reading> prev = repository.findPrevReading(equipmentId, sampledAt);
        if (prev.isPresent() && cumulativeMinutes < prev.get().cumulativeMinutes()) {
            throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                    "累计工时小于前一条读数（" + prev.get().cumulativeMinutes() + " 分钟），违反单调不减约束");
        }
        Optional<Reading> next = repository.findNextReading(equipmentId, sampledAt);
        if (next.isPresent() && cumulativeMinutes > next.get().cumulativeMinutes()) {
            throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                    "累计工时大于后一条读数（" + next.get().cumulativeMinutes() + " 分钟），违反单调不减约束");
        }
    }

    /** 登记设备幂等指纹（外观层并发补偿重放须使用同一规范形式）。 */
    static String registerFingerprint(String equipmentId, ResolvedPeriod period) {
        return equipmentId + "|" + period.unit() + "|" + period.value() + "|" + period.minutes();
    }

    /**
     * 解析登记设备的保养周期：measurementUnit 缺省 MINUTES；
     * maintenancePeriodValue（登记单位十进制）优先，否则用 maintenancePeriodMinutes 换算。
     */
    static ResolvedPeriod resolvePeriod(RegisterEquipmentRequest req) {
        MeasurementUnit unit = req.measurementUnit() == null
                ? MeasurementUnit.MINUTES
                : MeasurementUnit.valueOf(req.measurementUnit());
        BigDecimal value;
        if (req.maintenancePeriodValue() != null) {
            value = req.maintenancePeriodValue();
        } else if (req.maintenancePeriodMinutes() != null) {
            value = UnitConverter.convert(BigDecimal.valueOf(req.maintenancePeriodMinutes()),
                    MeasurementUnit.MINUTES, unit);
        } else {
            throw ApiException.badRequest("PERIOD_REQUIRED",
                    "须提供 maintenancePeriodValue 或 maintenancePeriodMinutes");
        }
        if (!UnitConverter.hasValidScale(value)) {
            throw ApiException.badRequest("VALUE_SCALE_EXCEEDED", "保养周期最多允许 2 位小数");
        }
        BigDecimal normalized = UnitConverter.normalize(value);
        return new ResolvedPeriod(unit, normalized, UnitConverter.toMinutes(normalized, unit));
    }

    /**
     * 解析提交读数：unit 缺省为设备登记单位；cumulativeValue（提交单位十进制）优先，
     * 否则用 cumulativeMinutes。换算为设备登记单位存储，并得到统一口径分钟数。
     * 提交单位与登记单位不同时由调用方凭 sourceUnit 写换算留痕。
     */
    private ResolvedValue resolveReadingValue(Equipment equipment, String unitName,
                                              BigDecimal submittedValue, Long submittedMinutes) {
        MeasurementUnit sourceUnit = unitName == null
                ? equipment.measurementUnit()
                : MeasurementUnit.valueOf(unitName);
        BigDecimal sourceValue;
        if (submittedValue != null) {
            sourceValue = submittedValue;
        } else if (submittedMinutes != null) {
            // 兼容字段 cumulativeMinutes 始终以分钟解释，忽略 unit 标签
            sourceValue = BigDecimal.valueOf(submittedMinutes);
            sourceUnit = MeasurementUnit.MINUTES;
        } else {
            throw ApiException.badRequest("VALUE_REQUIRED",
                    "须提供 cumulativeValue 或 cumulativeMinutes");
        }
        if (!UnitConverter.hasValidScale(sourceValue)) {
            throw ApiException.badRequest("VALUE_SCALE_EXCEEDED", "读数值最多允许 2 位小数");
        }
        BigDecimal normalized = UnitConverter.normalize(sourceValue);
        BigDecimal equipmentValue = UnitConverter.convert(normalized, sourceUnit,
                equipment.measurementUnit());
        long minutes = UnitConverter.toMinutes(equipmentValue, equipment.measurementUnit());
        return new ResolvedValue(sourceUnit, normalized, equipmentValue, minutes);
    }

    /** 提交单位与设备登记单位不同时追加只读换算留痕（不产生额外读数条目）。 */
    private void recordConversionIfNeeded(Equipment equipment, String readingId, int revisionNo,
                                          ResolvedValue value, String requestId, Instant now) {
        if (value.sourceUnit() == equipment.measurementUnit()) {
            return;
        }
        repository.insertConversion(new ConversionRecord(0L, equipment.equipmentId(), readingId,
                revisionNo, value.sourceUnit(), value.sourceValue(), value.equipmentValue(),
                value.minutes(), requestId, now));
    }

    private MeasurementUnit parseDisplayUnit(String displayUnitName, Equipment equipment) {
        if (displayUnitName == null || displayUnitName.isBlank()) {
            return equipment.measurementUnit();
        }
        try {
            return MeasurementUnit.valueOf(displayUnitName);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("UNSUPPORTED_UNIT",
                    "展示单位仅支持 MINUTES 或 HOURS：" + displayUnitName);
        }
    }

    record ResolvedPeriod(MeasurementUnit unit, BigDecimal value, long minutes) {
    }

    private record ResolvedValue(MeasurementUnit sourceUnit, BigDecimal sourceValue,
                                 BigDecimal equipmentValue, long minutes) {
    }
}
