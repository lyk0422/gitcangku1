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
import com.example.starter.maintenance.api.dto.MeterChainResponse;
import com.example.starter.maintenance.api.dto.MeterReplacementRequest;
import com.example.starter.maintenance.api.dto.MeterView;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReplacementView;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Meter;
import com.example.starter.maintenance.domain.MeterReplacement;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 *
 * <p>工时表更换链语义：每台设备一条链（chain_seq 递增），任意时刻仅一张 ACTIVE 表；
 * 虚拟工时 virtual = raw + 表 offset，链式连续性以各表最后有效读数为准：
 * offset(后继) = virtual(前驱最后有效读数) - initialRawHours(后继)。
 * finalRawHours 为关闭时申报的封顶值（不小于关闭时最后有效读数），仅约束关闭后的修订，不进入 offset 公式。
 * 已关闭表最后有效读数被修订时，在同一事务内沿全部后继表重算 offset、虚拟工时与保养锚点，
 * 并重算到期状态；若重算产生倒退、跨表重叠或使已记录保养发生在未来工时，整次 422 回滚。
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
                    Instant now = clock.instant();
                    repository.insertEquipment(req.equipmentId(), req.maintenancePeriodMinutes(), now);
                    // 初始工时表：起始原始读数 0、offset 0，虚拟工时与原始读数一致
                    repository.insertMeter(new Meter(initialMeterKey(req.equipmentId()), req.equipmentId(),
                            0, Meter.STATUS_ACTIVE, 0L, null, 0L), now);
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
                    Meter meter = activeMeter(equipmentId);
                    checkMeterFloor(meter, req.cumulativeMinutes());
                    if (repository.findReading(equipmentId, req.readingId()).isPresent()) {
                        throw ApiException.conflict("READING_EXISTS", "读数已存在：" + req.readingId());
                    }
                    if (repository.findReadingAt(equipmentId, req.sampledAt()).isPresent()) {
                        throw ApiException.unprocessable("READING_TIME_DUPLICATE",
                                "同一设备同一采样时刻仅允许一条读数");
                    }
                    checkMonotonic(meter.meterKey(), req.sampledAt(), req.cumulativeMinutes());
                    Instant now = clock.instant();
                    repository.insertReading(
                            new Reading(equipmentId, req.readingId(), meter.meterKey(), req.sampledAt(),
                                    req.cumulativeMinutes(), 1),
                            now);
                    repository.insertRevision(equipmentId, req.readingId(), 1,
                            req.cumulativeMinutes(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return toReadingResponse(equipmentId, req.readingId(), meter, req.sampledAt(),
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
                    Meter meter = repository.findMeter(reading.meterKey())
                            .orElseThrow(() -> new IllegalStateException(
                                    "读数所属工时表不存在：" + reading.meterKey()));
                    if (repository.existsMaintenanceAnchoringReading(equipmentId, readingId)) {
                        throw ApiException.conflict("READING_ANCHORED",
                                "读数已作为历史保养锚点，不可修订：" + readingId);
                    }
                    // 跨表重叠防线：不得低于所属表起始读数（否则虚拟工时倒退回前驱表区间）
                    checkMeterFloor(meter, req.cumulativeMinutes());
                    // 已关闭表：修订不得超过申报的 finalRawHours
                    if (!meter.isActive() && req.cumulativeMinutes() > meter.finalRawHours()) {
                        throw ApiException.unprocessable("REVISION_EXCEEDS_METER_FINAL",
                                "已关闭表读数修订不得超过申报最终读数 " + meter.finalRawHours());
                    }
                    checkMonotonic(meter.meterKey(), reading.sampledAt(), req.cumulativeMinutes());
                    int newRevisionNo = reading.revisionNo() + 1;
                    Instant now = clock.instant();
                    repository.updateReadingValue(equipmentId, readingId, req.cumulativeMinutes(),
                            newRevisionNo, now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            req.cumulativeMinutes(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    // 已关闭表的最后有效读数被修订：同事务沿全部后继表重算
                    boolean lastValidChanged = !meter.isActive()
                            && req.cumulativeMinutes() != reading.cumulativeMinutes()
                            && isLastReadingOfMeter(meter.meterKey(), readingId);
                    if (lastValidChanged) {
                        recomputeChain(equipmentId, meter.chainSeq());
                    }
                    return toReadingResponse(equipmentId, readingId, meter, reading.sampledAt(),
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
                    Meter meter = repository.findMeter(anchor.meterKey())
                            .orElseThrow(() -> new IllegalStateException(
                                    "读数所属工时表不存在：" + anchor.meterKey()));
                    if (!meter.isActive()) {
                        throw ApiException.unprocessable("ANCHOR_METER_NOT_ACTIVE",
                                "仅可锚定当前 ACTIVE 表的读数，该读数所属表已关闭：" + meter.meterKey());
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
                    long anchorVirtual = anchor.cumulativeMinutes() + meter.offsetHours();
                    Instant now = clock.instant();
                    long maintenanceId = repository.insertMaintenance(equipmentId, req.readingId(),
                            meter.meterKey(), req.anchorRevisionNo(), anchor.sampledAt(),
                            anchor.cumulativeMinutes(), anchorVirtual, req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, req.readingId(),
                            meter.meterKey(), req.anchorRevisionNo(), anchor.sampledAt(),
                            anchor.cumulativeMinutes(), anchorVirtual, now, equipment.version() + 1);
                });
    }

    // ---------- 工时表更换 ----------

    @Transactional
    public MeterChainResponse replaceMeter(String equipmentId, MeterReplacementRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.replacementKey() + "|" + req.newMeterKey()
                + "|" + req.oldLastReadingVersion() + "|" + req.finalRawHours()
                + "|" + req.initialRawHours() + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "REPLACE_METER", fingerprint,
                MeterChainResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    Meter oldMeter = activeMeter(equipmentId);
                    Reading lastReading = repository.findLatestReadingOnMeter(oldMeter.meterKey())
                            .orElseThrow(() -> ApiException.unprocessable("METER_NO_READINGS",
                                    "当前工时表没有任何读数，无法登记更换：" + oldMeter.meterKey()));
                    if (lastReading.revisionNo() != req.oldLastReadingVersion()) {
                        throw ApiException.conflict("METER_LAST_READING_VERSION_CONFLICT",
                                "旧表最后一条读数版本不一致：期望 " + req.oldLastReadingVersion()
                                        + "，当前 " + lastReading.revisionNo());
                    }
                    if (req.finalRawHours() < lastReading.cumulativeMinutes()) {
                        throw ApiException.unprocessable("FINAL_RAW_BELOW_LAST_READING",
                                "申报最终读数 " + req.finalRawHours() + " 小于旧表最后有效读数 "
                                        + lastReading.cumulativeMinutes());
                    }
                    if (repository.findReplacement(req.replacementKey()).isPresent()) {
                        throw ApiException.conflict("REPLACEMENT_KEY_EXISTS",
                                "更换记录标识已存在：" + req.replacementKey());
                    }
                    if (repository.findMeter(req.newMeterKey()).isPresent()) {
                        throw ApiException.conflict("METER_KEY_EXISTS",
                                "工时表标识已存在（更换链不可成环）：" + req.newMeterKey());
                    }
                    // 冻结新表 offset：以旧表最后有效读数的虚拟工时为链式连续基准
                    long newOffset = lastReading.cumulativeMinutes() + oldMeter.offsetHours()
                            - req.initialRawHours();
                    Instant now = clock.instant();
                    repository.closeMeter(oldMeter.meterKey(), req.finalRawHours());
                    repository.insertMeter(new Meter(req.newMeterKey(), equipmentId,
                            oldMeter.chainSeq() + 1, Meter.STATUS_ACTIVE,
                            req.initialRawHours(), null, newOffset), now);
                    repository.insertReplacement(new MeterReplacement(req.replacementKey(), equipmentId,
                            oldMeter.meterKey(), req.newMeterKey(), lastReading.readingId(),
                            lastReading.revisionNo(), req.finalRawHours(), req.initialRawHours(),
                            newOffset, req.requestId(), now));
                    repository.incrementVersion(equipmentId);
                    return buildChainSnapshot(equipmentId);
                });
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public StatusResponse getStatus(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        Meter meter = activeMeter(equipmentId);
        Optional<Reading> latest = repository.findLatestReadingOnMeter(meter.meterKey());
        Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        // 当前表尚无读数时，当前虚拟工时为该表基准虚拟工时（承接前驱表最后有效读数）
        long latestVirtual = latest.map(reading -> reading.cumulativeMinutes() + meter.offsetHours())
                .orElseGet(meter::baseVirtualHours);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long anchorVirtual = last.map(MaintenanceRecord::anchorVirtualHours).orElse(0L);
        long runMinutes = latestVirtual - anchorVirtual;
        String status = runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK";
        return new StatusResponse(equipmentId, equipment.version(), equipment.recalcVersion(),
                equipment.maintenancePeriodMinutes(), meter.meterKey(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative, latestVirtual,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                anchorVirtual, runMinutes, status);
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        Map<String, Meter> metersByKey = repository.listMeters(equipmentId).stream()
                .collect(Collectors.toMap(Meter::meterKey, Function.identity()));
        return repository.listReadings(equipmentId).stream()
                .map(reading -> {
                    Meter meter = metersByKey.get(reading.meterKey());
                    return toReadingResponse(equipmentId, reading.readingId(), meter, reading.sampledAt(),
                            reading.cumulativeMinutes(), reading.revisionNo(),
                            repository.existsMaintenanceAnchoringReading(equipmentId, reading.readingId()),
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
                        row.requestId(), row.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listMaintenances(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listMaintenances(equipmentId).stream()
                .map(record -> new MaintenanceResponse(record.maintenanceId(), equipmentId,
                        record.readingId(), record.meterKey(), record.anchorRevisionNo(),
                        record.anchorSampledAt(), record.anchorCumulativeMinutes(),
                        record.anchorVirtualHours(), record.completedAt(), 0L))
                .toList();
    }

    /** 更换链与重算版本只读查询。 */
    @Transactional(readOnly = true)
    public MeterChainResponse getMeterChain(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return buildChainSnapshot(equipmentId);
    }

    // ---------- 链式重算 ----------

    /**
     * 已关闭表（fromChainSeq）最后有效读数变化后的全链重算：沿全部后继表重算冻结 offset
     * （从而重算所有后续虚拟工时）、保养锚点虚拟工时，并做一致性校验。
     * 与触发它的修订在同一事务执行；校验失败抛 422，整次回滚、原历史不变。
     */
    private void recomputeChain(String equipmentId, int fromChainSeq) {
        List<Meter> meters = repository.listMeters(equipmentId);
        long predecessorEndVirtual = lastValidVirtual(meters.get(fromChainSeq));
        for (int i = fromChainSeq + 1; i < meters.size(); i++) {
            Meter meter = meters.get(i);
            long newOffset = predecessorEndVirtual - meter.initialRawHours();
            if (newOffset != meter.offsetHours()) {
                repository.updateMeterOffset(meter.meterKey(), newOffset);
                // 该表全部保养锚点虚拟工时随新 offset 重算（原始快照不变）
                repository.recomputeAnchorVirtualHours(meter.meterKey(), newOffset);
                meter = new Meter(meter.meterKey(), meter.equipmentId(), meter.chainSeq(), meter.status(),
                        meter.initialRawHours(), meter.finalRawHours(), newOffset);
            }
            predecessorEndVirtual = lastValidVirtual(meter);
        }
        repository.incrementRecalcVersion(equipmentId);
        validateChainConsistency(equipmentId);
    }

    /** 该表最后有效读数的虚拟工时；已关闭表保证至少一条读数。 */
    private long lastValidVirtual(Meter meter) {
        Reading last = repository.findLatestReadingOnMeter(meter.meterKey())
                .orElseThrow(() -> new IllegalStateException(
                        "已关闭工时表缺少读数：" + meter.meterKey()));
        return last.cumulativeMinutes() + meter.offsetHours();
    }

    /**
     * 重算后一致性防线：任一违背即 422 整体回滚——
     * 各表最后有效虚拟工时不得倒退回本表基准之下（倒退/跨表重叠）；
     * 保养锚点虚拟工时按锚点时间不得倒退；已记录保养不得晚于设备当前虚拟工时（未来工时）。
     */
    private void validateChainConsistency(String equipmentId) {
        List<Meter> meters = repository.listMeters(equipmentId);
        for (Meter meter : meters) {
            Optional<Reading> last = repository.findLatestReadingOnMeter(meter.meterKey());
            if (last.isPresent()
                    && last.get().cumulativeMinutes() + meter.offsetHours() < meter.baseVirtualHours()) {
                throw ApiException.unprocessable("RECALC_CHAIN_REGRESSION",
                        "重算导致工时表 " + meter.meterKey() + " 虚拟工时倒退/跨表重叠");
            }
        }
        Meter active = meters.get(meters.size() - 1);
        long currentVirtual = repository.findLatestReadingOnMeter(active.meterKey())
                .map(reading -> reading.cumulativeMinutes() + active.offsetHours())
                .orElseGet(active::baseVirtualHours);
        List<MaintenanceRecord> maintenances = repository.listMaintenances(equipmentId);
        long previousAnchorVirtual = Long.MIN_VALUE;
        for (MaintenanceRecord record : maintenances) {
            if (record.anchorVirtualHours() < previousAnchorVirtual) {
                throw ApiException.unprocessable("RECALC_ANCHOR_REGRESSION",
                        "重算导致保养锚点虚拟工时倒退：" + record.maintenanceId());
            }
            if (record.anchorVirtualHours() > currentVirtual) {
                throw ApiException.unprocessable("RECALC_MAINTENANCE_IN_FUTURE",
                        "重算导致已记录保养发生在未来工时：" + record.maintenanceId());
            }
            previousAnchorVirtual = record.anchorVirtualHours();
        }
    }

    // ---------- 内部规则 ----------

    private String initialMeterKey(String equipmentId) {
        return equipmentId + "#meter-0";
    }

    private Equipment lockEquipment(String equipmentId) {
        return repository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
    }

    private Meter activeMeter(String equipmentId) {
        return repository.findActiveMeter(equipmentId)
                .orElseThrow(() -> new IllegalStateException("设备缺少 ACTIVE 工时表：" + equipmentId));
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

    /** 读数/修订值不得低于所属表起始读数，否则虚拟工时倒退回前驱表区间（跨表重叠）。 */
    private void checkMeterFloor(Meter meter, long cumulativeMinutes) {
        if (cumulativeMinutes < meter.initialRawHours()) {
            throw ApiException.unprocessable("READING_BELOW_METER_INITIAL",
                    "读数 " + cumulativeMinutes + " 小于工时表起始读数 " + meter.initialRawHours()
                            + "，会造成跨表重叠");
        }
    }

    /** 单调性校验：新值须同时不早于前相邻读数、不晚于后相邻读数（同一工时表内按采样时刻排序）。 */
    private void checkMonotonic(String meterKey, Instant sampledAt, long cumulativeMinutes) {
        Optional<Reading> prev = repository.findPrevReading(meterKey, sampledAt);
        if (prev.isPresent() && cumulativeMinutes < prev.get().cumulativeMinutes()) {
            throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                    "累计工时小于前一条读数（" + prev.get().cumulativeMinutes() + "），违反单调不减约束");
        }
        Optional<Reading> next = repository.findNextReading(meterKey, sampledAt);
        if (next.isPresent() && cumulativeMinutes > next.get().cumulativeMinutes()) {
            throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                    "累计工时大于后一条读数（" + next.get().cumulativeMinutes() + "），违反单调不减约束");
        }
    }

    private boolean isLastReadingOfMeter(String meterKey, String readingId) {
        return repository.findLatestReadingOnMeter(meterKey)
                .map(reading -> reading.readingId().equals(readingId))
                .orElse(false);
    }

    private ReadingResponse toReadingResponse(String equipmentId, String readingId, Meter meter,
                                              Instant sampledAt, long cumulativeMinutes, int revisionNo,
                                              boolean anchored, long equipmentVersion) {
        return new ReadingResponse(equipmentId, readingId, meter.meterKey(), sampledAt, cumulativeMinutes,
                cumulativeMinutes + meter.offsetHours(), revisionNo, anchored, equipmentVersion);
    }

    /** 完整链快照：更换登记响应、链查询与重算版本查询共用。 */
    private MeterChainResponse buildChainSnapshot(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        List<MeterView> meters = repository.listMeters(equipmentId).stream()
                .map(meter -> {
                    Optional<Reading> last = repository.findLatestReadingOnMeter(meter.meterKey());
                    return new MeterView(meter.meterKey(), meter.chainSeq(), meter.status(),
                            meter.initialRawHours(), meter.finalRawHours(), meter.offsetHours(),
                            meter.baseVirtualHours(),
                            last.map(Reading::cumulativeMinutes).orElse(null),
                            last.map(reading -> reading.cumulativeMinutes() + meter.offsetHours())
                                    .orElse(null));
                })
                .toList();
        List<ReplacementView> replacements = repository.listReplacements(equipmentId).stream()
                .map(replacement -> new ReplacementView(replacement.replacementKey(),
                        replacement.oldMeterKey(), replacement.newMeterKey(),
                        replacement.oldLastReadingId(), replacement.oldLastReadingVersion(),
                        replacement.finalRawHours(), replacement.initialRawHours(),
                        replacement.offsetHours(), replacement.createdAt()))
                .toList();
        return new MeterChainResponse(equipmentId, equipment.version(), equipment.recalcVersion(),
                meters, replacements);
    }
}
