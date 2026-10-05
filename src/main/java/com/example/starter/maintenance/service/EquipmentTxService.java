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
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.EquivalentLife;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚，不推进版本、不写修订。
 * 等效累计（负荷加权）由当前读数序列整体推导，更正读数后其与后一条间隔的贡献同步重算；
 * 保养完成时在行锁内冻结当时最新读数标识与等效累计，历史快照不再变化。
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
        return idempotency.execute(req.requestId(), "REGISTER_EQUIPMENT", registerFingerprint(req),
                EquipmentResponse.class, () -> {
                    if (repository.findEquipment(req.equipmentId()).isPresent()) {
                        throw ApiException.conflict("EQUIPMENT_EXISTS", "设备已存在：" + req.equipmentId());
                    }
                    repository.insertEquipment(req.equipmentId(), req.maintenancePeriodMinutes(),
                            req.equivalentPeriodMinutes(), clock.instant());
                    return new EquipmentResponse(req.equipmentId(), req.maintenancePeriodMinutes(),
                            req.equivalentPeriodMinutes(), 1L);
                });
    }

    /** 登记设备指纹（与事务内执行所用一致，供并发唯一键冲突补偿重放比对）。 */
    public String registerFingerprint(RegisterEquipmentRequest req) {
        return RequestFingerprint.of("REGISTER_EQUIPMENT")
                .field("equipmentId", req.equipmentId())
                .field("maintenancePeriodMinutes", req.maintenancePeriodMinutes())
                .field("equivalentPeriodMinutes", req.equivalentPeriodMinutes())
                .build();
    }

    // ---------- 新增读数 ----------

    @Transactional
    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = RequestFingerprint.of("ADD_READING")
                .field("equipmentId", equipmentId)
                .field("readingId", req.readingId())
                .field("sampledAt", req.sampledAt())
                .field("cumulativeMinutes", req.cumulativeMinutes())
                .field("loadWeight", req.loadWeight())
                .field("expectedVersion", req.expectedVersion())
                .build();
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
                                    req.cumulativeMinutes(), req.loadWeight(), 1),
                            now);
                    repository.insertRevision(equipmentId, req.readingId(), 1,
                            req.cumulativeMinutes(), req.loadWeight(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return readingView(equipmentId, req.readingId(), equipment.version() + 1);
                });
    }

    // ---------- 修订读数 ----------

    @Transactional
    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = RequestFingerprint.of("REVISE_READING")
                .field("equipmentId", equipmentId)
                .field("readingId", readingId)
                .field("cumulativeMinutes", req.cumulativeMinutes())
                .field("loadWeight", req.loadWeight())
                .field("expectedVersion", req.expectedVersion())
                .build();
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
                    String newLoadWeight = req.loadWeight() != null ? req.loadWeight() : reading.loadWeight();
                    int newRevisionNo = reading.revisionNo() + 1;
                    Instant now = clock.instant();
                    repository.updateReadingValue(equipmentId, readingId, req.cumulativeMinutes(),
                            newLoadWeight, newRevisionNo, now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            req.cumulativeMinutes(), newLoadWeight, req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return readingView(equipmentId, readingId, equipment.version() + 1);
                });
    }

    // ---------- 完成保养 ----------

    @Transactional
    public MaintenanceResponse completeMaintenance(String equipmentId, CompleteMaintenanceRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = RequestFingerprint.of("COMPLETE_MAINTENANCE")
                .field("equipmentId", equipmentId)
                .field("readingId", req.readingId())
                .field("anchorRevisionNo", req.anchorRevisionNo())
                .field("expectedVersion", req.expectedVersion())
                .build();
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
                    // 行锁 + 版本校验内冻结：基准等效值与读数当前值一致，不会取到已被并发更正的旧值
                    List<Reading> readings = repository.listReadings(equipmentId);
                    Reading latest = readings.get(readings.size() - 1);
                    long equivalentTotal = EquivalentLife.totalOf(readings);
                    Instant now = clock.instant();
                    long maintenanceId = repository.insertMaintenance(equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            latest.readingId(), equivalentTotal, req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            latest.readingId(), equivalentTotal, now, equipment.version() + 1);
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
        long remainingMinutes = equipment.maintenancePeriodMinutes() - runMinutes;
        String status = remainingMinutes <= 0 ? "DUE" : "OK";

        Long equivalentPeriod = equipment.equivalentPeriodMinutes();
        Long latestEquivalent = null;
        Long equivalentRun = null;
        Long equivalentRemaining = null;
        String equivalentStatus = null;
        if (equivalentPeriod != null) {
            latestEquivalent = EquivalentLife.totalOf(repository.listReadings(equipmentId));
            long anchorEquivalent = last.map(MaintenanceRecord::anchorEquivalentCumulativeMinutes).orElse(0L);
            equivalentRun = latestEquivalent - anchorEquivalent;
            equivalentRemaining = equivalentPeriod - equivalentRun;
            equivalentStatus = equivalentRemaining <= 0 ? "DUE" : "OK";
        }
        return new StatusResponse(equipmentId, equipment.version(), equipment.maintenancePeriodMinutes(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                runMinutes, remainingMinutes, status,
                equivalentPeriod, latestEquivalent, equivalentRun, equivalentRemaining, equivalentStatus);
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        List<Reading> readings = repository.listReadings(equipmentId);
        List<Long> equivalents = EquivalentLife.prefixTotals(readings);
        return java.util.stream.IntStream.range(0, readings.size())
                .mapToObj(i -> {
                    Reading reading = readings.get(i);
                    return new ReadingResponse(equipmentId, reading.readingId(), reading.sampledAt(),
                            reading.cumulativeMinutes(), reading.loadWeight(), equivalents.get(i),
                            reading.revisionNo(),
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
                        row.loadWeight(), row.requestId(), row.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listMaintenances(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listMaintenances(equipmentId).stream()
                .map(record -> new MaintenanceResponse(record.maintenanceId(), equipmentId,
                        record.readingId(), record.anchorRevisionNo(), record.anchorSampledAt(),
                        record.anchorCumulativeMinutes(), record.latestReadingId(),
                        record.anchorEquivalentCumulativeMinutes(), record.completedAt(), 0L))
                .toList();
    }

    // ---------- 内部规则 ----------

    /** 指定读数的当前视图（含由当前读数序列推导的等效累计）。 */
    private ReadingResponse readingView(String equipmentId, String readingId, long equipmentVersion) {
        List<Reading> readings = repository.listReadings(equipmentId);
        List<Long> equivalents = EquivalentLife.prefixTotals(readings);
        for (int i = 0; i < readings.size(); i++) {
            Reading reading = readings.get(i);
            if (reading.readingId().equals(readingId)) {
                return new ReadingResponse(equipmentId, readingId, reading.sampledAt(),
                        reading.cumulativeMinutes(), reading.loadWeight(), equivalents.get(i),
                        reading.revisionNo(),
                        repository.existsMaintenanceAnchoringReading(equipmentId, readingId),
                        equipmentVersion);
            }
        }
        throw new IllegalStateException("读数视图构建失败：" + readingId);
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
