package com.example.starter.maintenance.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.ChainResponse;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.MeterResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RecomputeView;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReplacementResponse;
import com.example.starter.maintenance.api.dto.ReplaceMeterRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Meter;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.EquipmentRepository;
import com.example.starter.maintenance.store.EquipmentRepository.RecomputeRow;
import com.example.starter.maintenance.store.EquipmentRepository.RevisionRow;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 工时以小时（DECIMAL）存储，虚拟工时 virtualHours(meter, raw) = offset + raw - initial，
 * 沿更换链冻结偏移使跨表读数连续；旧表最后有效读数修订在同事务内整链重算，任一规则失败整体回滚。
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

    // ---------- 登记设备（同时产生初始 ACTIVE 工时表） ----------

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
                    repository.insertMeter(new Meter(
                            req.equipmentId(), req.equipmentId(), Meter.ACTIVE, 0, null, null,
                            BigDecimal.ZERO, null, BigDecimal.ZERO, 1, now, null));
                    return new EquipmentResponse(req.equipmentId(), req.maintenancePeriodMinutes(), 1L);
                });
    }

    // ---------- 新增读数（登记到当前 ACTIVE 工时表） ----------

    @Transactional
    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.readingId() + "|" + req.sampledAt()
                + "|" + req.rawHours().stripTrailingZeros().toPlainString() + "|" + req.expectedVersion();
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
                    Meter meter = activeMeter(equipmentId);
                    checkRawWithinMeter(meter, req.sampledAt(), req.rawHours());
                    BigDecimal virtual = meter.mapVirtual(req.rawHours());
                    Reading reading = new Reading(equipmentId, req.readingId(), meter.meterKey(),
                            req.sampledAt(), normalized(req.rawHours()), virtual, 1);
                    Instant now = clock.instant();
                    repository.insertReading(reading, toMinutes(virtual), now);
                    repository.insertRevision(equipmentId, req.readingId(), 1,
                            normalized(req.rawHours()), toMinutes(virtual), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return toReadingResponse(reading, false, equipment.version() + 1);
                });
    }

    // ---------- 修订读数（可能触发整链重算） ----------

    @Transactional
    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + readingId + "|"
                + req.rawHours().stripTrailingZeros().toPlainString() + "|" + req.expectedVersion();
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
                    Meter meter = repository.findMeter(equipmentId, reading.meterKey())
                            .orElseThrow(() -> new IllegalStateException("读数所属工时表丢失：" + reading.meterKey()));
                    BigDecimal newRaw = normalized(req.rawHours());
                    // 表内规则：前后相邻单调、不低于本表初始读数；已关闭表不得超过 finalRawHours。
                    checkRawWithinMeter(meter, reading.sampledAt(), newRaw);
                    if (!meter.active() && newRaw.compareTo(meter.finalRawHours()) > 0) {
                        throw ApiException.unprocessable("CLOSED_METER_FINAL_EXCEEDED",
                                "修订值不得超过该表关表最终原始读数 " + meter.finalRawHours().toPlainString());
                    }

                    BigDecimal newVirtual = meter.mapVirtual(newRaw);
                    List<Meter> chain = repository.listMeters(equipmentId);

                    // 仅当被修订读数是本表最后有效读数且本表之后仍有后继表时，偏移影响才会跨表传播。
                    boolean crossMeter = repository.findLatestReadingInMeter(meter.meterKey())
                            .map(Reading::readingId)
                            .map(readingId::equals)
                            .orElse(false)
                            && chain.stream().anyMatch(m -> m.seqNo() > meter.seqNo());
                    ChainProspect prospect = null;
                    if (crossMeter) {
                        prospect = buildRecomputeProspect(equipmentId, chain, meter, reading,
                                newRaw, newVirtual);
                    }

                    int newRevisionNo = reading.revisionNo() + 1;
                    Instant now = clock.instant();
                    repository.updateReadingValue(equipmentId, readingId, newRaw, newVirtual,
                            toMinutes(newVirtual), newRevisionNo, now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            newRaw, toMinutes(newVirtual), req.requestId(), now);

                    if (crossMeter) {
                        applyRecomputeProspect(equipmentId, prospect, meter, reading,
                                newRaw, req.requestId(), now);
                    }
                    repository.incrementVersion(equipmentId);

                    Reading updated = new Reading(equipmentId, readingId, meter.meterKey(),
                            reading.sampledAt(), newRaw, newVirtual, newRevisionNo);
                    return toReadingResponse(updated, false, equipment.version() + 1);
                });
    }

    // ---------- 工时表更换 ----------

    @Transactional
    public ReplacementResponse replaceMeter(String equipmentId, ReplaceMeterRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.newMeterKey() + "|"
                + req.finalRawHours().stripTrailingZeros().toPlainString() + "|"
                + req.initialRawHours().stripTrailingZeros().toPlainString() + "|"
                + req.expectedVersion() + "|" + req.lastReadingRevisionNo();
        return idempotency.execute(req.replacementKey(), "REPLACE_METER", fingerprint,
                ReplacementResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    Meter old = activeMeter(equipmentId);
                    BigDecimal finalRaw = normalized(req.finalRawHours());
                    BigDecimal initialRaw = normalized(req.initialRawHours());

                    Optional<Reading> lastReading = repository.findLatestReadingInMeter(old.meterKey());
                    if (lastReading.isPresent() && finalRaw.compareTo(lastReading.get().rawHours()) < 0) {
                        throw ApiException.unprocessable("FINAL_BELOW_LAST_READING",
                                "finalRawHours 不得小于旧表最后有效读数 "
                                        + lastReading.get().rawHours().toPlainString());
                    }
                    if (req.lastReadingRevisionNo() != null && lastReading.isPresent()
                            && req.lastReadingRevisionNo() != lastReading.get().revisionNo()) {
                        throw ApiException.conflict("LAST_READING_VERSION_CONFLICT",
                                "旧表最后一条读数版本已变化：期望 " + req.lastReadingRevisionNo()
                                        + "，当前 " + lastReading.get().revisionNo());
                    }
                    // meterKey 全局唯一；新 key 不得等于链上任何既有表（结构上杜绝成环）。
                    if (repository.findMeterByGlobalKey(req.newMeterKey()).isPresent()) {
                        throw ApiException.conflict("METER_KEY_EXISTS",
                                "meterKey 已被占用：" + req.newMeterKey());
                    }
                    for (Meter existing : repository.listMeters(equipmentId)) {
                        if (existing.meterKey().equals(req.newMeterKey())) {
                            throw ApiException.conflict("METER_KEY_EXISTS",
                                    "meterKey 已在本设备更换链上：" + req.newMeterKey());
                        }
                    }

                    Instant now = clock.instant();
                    BigDecimal boundaryVirtual = lastReading
                            .map(r -> old.mapVirtual(r.rawHours()))
                            .orElseGet(old::offsetHours);
                    Meter created = new Meter(equipmentId, req.newMeterKey(), Meter.ACTIVE,
                            old.seqNo() + 1, old.meterKey(), req.replacementKey(),
                            initialRaw, null, boundaryVirtual, 1, now, null);
                    repository.closeMeter(equipmentId, old.meterKey(), finalRaw, now);
                    repository.insertMeter(created);
                    repository.incrementVersion(equipmentId);

                    List<MeterResponse> chainView = repository.listMeters(equipmentId).stream()
                            .map(this::toMeterResponse).toList();
                    MeterResponse closedView = repository.findMeter(equipmentId, old.meterKey())
                            .map(this::toMeterResponse).orElseThrow();
                    return new ReplacementResponse(req.replacementKey(), equipmentId,
                            equipment.version() + 1, closedView, toMeterResponse(created), chainView);
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
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.virtualHours(),
                            req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.virtualHours(),
                            toMinutes(anchor.virtualHours()), now, equipment.version() + 1);
                });
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public StatusResponse getStatus(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        Meter active = repository.findActiveMeter(equipmentId).orElseThrow();
        Optional<Reading> latest = repository.findLatestReading(equipmentId);
        Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
        long latestMinutes = latest.map(Reading::virtualMinutes).orElse(0L);
        long anchorMinutes = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long runMinutes = latestMinutes - anchorMinutes;
        String status = runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK";
        return new StatusResponse(equipmentId, equipment.version(), equipment.maintenancePeriodMinutes(),
                active.meterKey(),
                latest.map(Reading::sampledAt).orElse(null),
                latest.map(Reading::rawHours).orElse(BigDecimal.ZERO),
                latest.map(Reading::virtualHours).orElse(BigDecimal.ZERO),
                latestMinutes,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null),
                last.map(MaintenanceRecord::anchorVirtualHours).orElse(BigDecimal.ZERO),
                anchorMinutes, runMinutes, status);
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listReadings(equipmentId).stream()
                .map(reading -> toReadingResponse(reading,
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
                .map(row -> new RevisionView(row.revisionNo(), row.rawHours(), row.cumulativeMinutes(),
                        row.requestId(), row.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listMaintenances(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listMaintenances(equipmentId).stream()
                .map(record -> new MaintenanceResponse(record.maintenanceId(), equipmentId,
                        record.readingId(), record.anchorRevisionNo(), record.anchorSampledAt(),
                        record.anchorVirtualHours(), record.anchorCumulativeMinutes(),
                        record.completedAt(), 0L))
                .toList();
    }

    /** 更换链与重算版本查询（只读）。 */
    @Transactional(readOnly = true)
    public ChainResponse getChain(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        List<MeterResponse> meters = repository.listMeters(equipmentId).stream()
                .map(this::toMeterResponse).toList();
        List<RecomputeView> recomputes = repository.listRecomputes(equipmentId).stream()
                .map(this::toRecomputeView).toList();
        String activeKey = meters.stream().filter(m -> Meter.ACTIVE.equals(m.status()))
                .map(MeterResponse::meterKey).findFirst().orElse(null);
        return new ChainResponse(equipmentId, activeKey, meters, recomputes);
    }

    // ---------- 整链重算 ----------

    /**
     * 应用任何写入前，推演旧表最后有效读数改为 newVirtual 后整条后继链的预期状态，并校验：
     * 1）倒退：各表读数按采样时刻虚拟工时仍单调不减，且不低于本表初始映射点；
     * 2）跨表重叠：后继表首条读数虚拟工时不低于前驱表边界（最后有效读数/初始点）虚拟工时；
     * 3）已记录保养不得落到未来工时：锚点按时间顺序不发生倒退，且最大锚点虚拟工时不超过当前最新读数。
     */
    private ChainProspect buildRecomputeProspect(String equipmentId, List<Meter> chain,
                                                 Meter changed, Reading changedReading,
                                                 BigDecimal newRaw, BigDecimal newBoundaryVirtual) {
        // 每张表推演后的 offset：被修订表 offset 冻结不变；其后每张表整体平移同一个 delta。
        BigDecimal delta = newBoundaryVirtual.subtract(
                changed.mapVirtual(changedReading.rawHours()));
        Map<String, BigDecimal> offsetByKey = new HashMap<>();
        Map<String, List<Reading>> readingsByKey = new HashMap<>();
        for (Meter meter : chain) {
            BigDecimal offset = meter.seqNo() > changed.seqNo()
                    ? meter.offsetHours().add(delta) : meter.offsetHours();
            offsetByKey.put(meter.meterKey(), offset);
            readingsByKey.put(meter.meterKey(), repository.listReadingsOfMeter(meter.meterKey()));
        }

        List<Reading> prospectAll = new ArrayList<>();
        Meter predecessor = null;
        BigDecimal predecessorBoundary = null;
        for (Meter meter : chain) {
            BigDecimal offset = offsetByKey.get(meter.meterKey());
            List<Reading> readings = readingsByKey.get(meter.meterKey());

            // 表内单调（被修订读数取新值）。
            BigDecimal previousVirtual = null;
            for (Reading r : readings) {
                BigDecimal raw = r.readingId().equals(changedReading.readingId()) ? newRaw : r.rawHours();
                BigDecimal virtual = offset.add(raw).subtract(meter.initialRawHours());
                if (virtual.compareTo(BigDecimal.ZERO) < 0) {
                    throw recompute422("RECOMPUTE_VIRTUAL_NEGATIVE",
                            "重算产生负的虚拟工时：读数 " + r.readingId());
                }
                if (previousVirtual != null && virtual.compareTo(previousVirtual) < 0) {
                    throw recompute422("RECOMPUTE_REGRESSION",
                            "重算导致表内虚拟工时倒退：读数 " + r.readingId());
                }
                if (raw.compareTo(meter.initialRawHours()) < 0) {
                    throw recompute422("RECOMPUTE_REGRESSION",
                            "重算后读数低于工时表初始原始读数：" + r.readingId());
                }
                previousVirtual = virtual;
            }

            // 跨表边界连续：本表首条读数不得低于前驱表边界虚拟工时。
            if (predecessor != null && !readings.isEmpty()) {
                Reading first = readings.get(0);
                BigDecimal firstVirtual = offset.add(first.rawHours()).subtract(meter.initialRawHours());
                if (firstVirtual.compareTo(predecessorBoundary) < 0) {
                    throw recompute422("RECOMPUTE_CROSS_METER_OVERLAP",
                            "重算导致跨表工时重叠：表 " + meter.meterKey() + " 首条读数 "
                                    + firstVirtual.toPlainString() + " 早于前驱表边界 "
                                    + predecessorBoundary.toPlainString());
                }
            }

            BigDecimal boundary;
            if (readings.isEmpty()) {
                boundary = offset;
            } else if (meter.meterKey().equals(changed.meterKey())) {
                boundary = newBoundaryVirtual;
            } else {
                Reading last = readings.get(readings.size() - 1);
                boundary = offset.add(last.rawHours()).subtract(meter.initialRawHours());
            }
            predecessor = meter;
            predecessorBoundary = boundary;
            readings.stream()
                    .map(r -> r.readingId().equals(changedReading.readingId())
                            ? new Reading(equipmentId, r.readingId(), meter.meterKey(), r.sampledAt(),
                            newRaw, newBoundaryVirtual, r.revisionNo())
                            : new Reading(equipmentId, r.readingId(), meter.meterKey(), r.sampledAt(),
                            r.rawHours(), offset.add(r.rawHours()).subtract(meter.initialRawHours()),
                            r.revisionNo()))
                    .forEach(prospectAll::add);
        }

        // 保养锚点：后继表锚点随 delta 平移；被修订表及其前序表锚点不可能锚定被修订读数（已 409 拦截）。
        List<MaintenanceRecord> records = repository.listMaintenances(equipmentId);
        BigDecimal previousAnchor = null;
        BigDecimal maxAnchor = null;
        Map<Long, BigDecimal> anchorVirtualById = new HashMap<>();
        for (MaintenanceRecord record : records) {
            Reading anchored = repository.findReading(equipmentId, record.readingId()).orElseThrow();
            Meter anchorMeter = chain.stream()
                    .filter(m -> m.meterKey().equals(anchored.meterKey())).findFirst().orElseThrow();
            BigDecimal anchorVirtual;
            if (anchored.readingId().equals(changedReading.readingId())) {
                continue; // 理论不可达：锚定读数不允许修订
            } else if (anchorMeter.seqNo() > changed.seqNo()) {
                anchorVirtual = record.anchorVirtualHours().add(delta);
            } else {
                anchorVirtual = record.anchorVirtualHours();
            }
            if (previousAnchor != null && anchorVirtual.compareTo(previousAnchor) < 0) {
                throw recompute422("RECOMPUTE_MAINTENANCE_REGRESSION",
                        "重算使已记录保养锚点工时倒退：保养 " + record.maintenanceId());
            }
            previousAnchor = anchorVirtual;
            if (maxAnchor == null || anchorVirtual.compareTo(maxAnchor) > 0) {
                maxAnchor = anchorVirtual;
            }
            anchorVirtualById.put(record.maintenanceId(), anchorVirtual);
        }
        Reading latest = prospectAll.stream()
                .max((a, b) -> a.sampledAt().compareTo(b.sampledAt()))
                .orElse(null);
        if (maxAnchor != null && latest != null && maxAnchor.compareTo(latest.virtualHours()) > 0) {
            throw recompute422("RECOMPUTE_MAINTENANCE_IN_FUTURE",
                    "重算使已记录保养锚点晚于当前最新读数，保养落入未来工时");
        }

        return new ChainProspect(delta, offsetByKey, readingsByKey, anchorVirtualById);
    }

    /** 按推演结果落库：后继表 offset/重算版本、全部后继读数虚拟工时、保养锚点快照、重算审计。 */
    private void applyRecomputeProspect(String equipmentId, ChainProspect prospect, Meter changed,
                                        Reading changedReading, BigDecimal newRaw,
                                        String requestId, Instant now) {
        for (Meter meter : repository.listMeters(equipmentId)) {
            if (meter.seqNo() <= changed.seqNo()) {
                continue;
            }
            BigDecimal newOffset = prospect.offsetByKey().get(meter.meterKey());
            repository.updateMeterOffset(equipmentId, meter.meterKey(), newOffset);
            for (Reading r : prospect.readingsByKey().get(meter.meterKey())) {
                BigDecimal virtual = newOffset.add(r.rawHours()).subtract(meter.initialRawHours());
                repository.updateReadingVirtual(equipmentId, r.readingId(), virtual, toMinutes(virtual));
            }
        }
        for (Map.Entry<Long, BigDecimal> entry : prospect.anchorVirtualById().entrySet()) {
            repository.updateMaintenanceAnchorVirtual(entry.getKey(), entry.getValue());
        }
        int recomputeNo = repository.nextRecomputeNo(equipmentId);
        repository.insertRecompute(equipmentId, recomputeNo, changed.meterKey(),
                changedReading.readingId(), requestId, changedReading.rawHours(), newRaw, now);
    }

    private record ChainProspect(BigDecimal delta, Map<String, BigDecimal> offsetByKey,
                                 Map<String, List<Reading>> readingsByKey,
                                 Map<Long, BigDecimal> anchorVirtualById) {
    }

    // ---------- 内部规则 ----------

    private Meter activeMeter(String equipmentId) {
        return repository.findActiveMeter(equipmentId)
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "NO_ACTIVE_METER", "设备不存在 ACTIVE 工时表：" + equipmentId));
    }

    /** 表内单调校验：不低于初始读数，且同时不早于前相邻读数、不晚于后相邻读数（按采样时刻排序）。 */
    private void checkRawWithinMeter(Meter meter, Instant sampledAt, BigDecimal rawHours) {
        if (rawHours.compareTo(meter.initialRawHours()) < 0) {
            throw ApiException.unprocessable("READING_BELOW_INITIAL",
                    "原始工时读数不得小于工时表初始原始读数 " + meter.initialRawHours().toPlainString());
        }
        Optional<Reading> prev = repository.findPrevReadingInMeter(meter.meterKey(), sampledAt);
        if (prev.isPresent() && rawHours.compareTo(prev.get().rawHours()) < 0) {
            throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                    "原始工时小于表内前一条读数（" + prev.get().rawHours().toPlainString()
                            + "），违反单调不减约束");
        }
        Optional<Reading> next = repository.findNextReadingInMeter(meter.meterKey(), sampledAt);
        if (next.isPresent() && rawHours.compareTo(next.get().rawHours()) > 0) {
            throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                    "原始工时大于表内后一条读数（" + next.get().rawHours().toPlainString()
                            + "），违反单调不减约束");
        }
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

    private ApiException recompute422(String code, String message) {
        return ApiException.unprocessable(code, message);
    }

    private MeterResponse toMeterResponse(Meter meter) {
        return new MeterResponse(meter.equipmentId(), meter.meterKey(), meter.status(), meter.seqNo(),
                meter.predecessorKey(), meter.replacementKey(), meter.initialRawHours(),
                meter.finalRawHours(), meter.offsetHours(), meter.recalcVersion(),
                meter.createdAt(), meter.closedAt());
    }

    private ReadingResponse toReadingResponse(Reading reading, boolean anchored, long equipmentVersion) {
        return new ReadingResponse(reading.equipmentId(), reading.readingId(), reading.meterKey(),
                reading.sampledAt(), reading.rawHours(), reading.virtualHours(),
                toMinutes(reading.virtualHours()), reading.revisionNo(), anchored, equipmentVersion);
    }

    private RecomputeView toRecomputeView(RecomputeRow row) {
        return new RecomputeView(row.recomputeNo(), row.triggeredMeterKey(), row.triggeredReadingId(),
                row.requestId(), row.fromRawHours(), row.toRawHours(), row.createdAt());
    }

    private static BigDecimal normalized(BigDecimal value) {
        return value.setScale(6, java.math.RoundingMode.HALF_UP);
    }

    private static long toMinutes(BigDecimal hours) {
        return hours.multiply(BigDecimal.valueOf(60))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
    }
}
