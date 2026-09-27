package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

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
import com.example.starter.maintenance.domain.IsolationOrder;
import com.example.starter.maintenance.domain.LockPoint;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.domain.RejectedReading;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 *
 * <p>能源隔离裁决：隔离令 EFFECTIVE 期间新增运行读数返回 409，证据（申报读数、锁定点集合快照、
 * 操作人）以独立事务固化，读数不落库、版本不前进、幂等键不占用；保养完成不受隔离限制。
 * 设备 DEACTIVATED 后所有工时/保养写入返回 422。</p>
 */
@Service
public class EquipmentTxService {

    public static final String OP_REGISTER_EQUIPMENT = "REGISTER_EQUIPMENT";
    public static final String OP_ADD_READING = "ADD_READING";
    public static final String OP_REVISE_READING = "REVISE_READING";
    public static final String OP_COMPLETE_MAINTENANCE = "COMPLETE_MAINTENANCE";
    public static final String REJECTED_REASON = "READING_REJECTED_UNDER_ISOLATION";

    private final EquipmentRepository repository;
    private final IdempotencyService idempotency;
    private final FingerprintService fingerprints;
    private final RejectedReadingRecorder rejectedRecorder;
    private final Clock clock;

    public EquipmentTxService(EquipmentRepository repository, IdempotencyService idempotency,
                              FingerprintService fingerprints, RejectedReadingRecorder rejectedRecorder,
                              Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.fingerprints = fingerprints;
        this.rejectedRecorder = rejectedRecorder;
        this.clock = clock;
    }

    // ---------- 登记设备 ----------

    @Transactional
    public EquipmentResponse register(RegisterEquipmentRequest req) {
        return idempotency.execute(req.requestId(), OP_REGISTER_EQUIPMENT, registerFingerprint(req),
                EquipmentResponse.class, () -> {
                    if (repository.findEquipment(req.equipmentId()).isPresent()) {
                        throw ApiException.conflict("EQUIPMENT_EXISTS", "设备已存在：" + req.equipmentId());
                    }
                    repository.insertEquipment(req.equipmentId(), req.maintenancePeriodMinutes(), clock.instant());
                    return new EquipmentResponse(req.equipmentId(), req.maintenancePeriodMinutes(), 1L);
                });
    }

    /** 登记设备指纹（外观服务并发补偿时须与事务内计算一致）。 */
    public static String registerFingerprint(RegisterEquipmentRequest req, FingerprintService fp) {
        var fields = new TreeMap<String, Object>();
        fields.put("operation", OP_REGISTER_EQUIPMENT);
        fields.put("equipmentId", req.equipmentId());
        fields.put("maintenancePeriodMinutes", req.maintenancePeriodMinutes());
        return fp.sha256(fields);
    }

    private String registerFingerprint(RegisterEquipmentRequest req) {
        return registerFingerprint(req, fingerprints);
    }

    // ---------- 新增读数 ----------

    @Transactional
    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        var fields = new TreeMap<String, Object>();
        fields.put("operation", OP_ADD_READING);
        fields.put("equipmentId", equipmentId);
        fields.put("readingId", req.readingId());
        fields.put("sampledAt", req.sampledAt());
        fields.put("cumulativeMinutes", req.cumulativeMinutes());
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("operatorId", req.operatorId());
        return idempotency.execute(req.requestId(), OP_ADD_READING, fingerprints.sha256(fields),
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    requireActiveEquipment(equipment);
                    rejectIfIsolated(equipment, req);
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
        var fields = new TreeMap<String, Object>();
        fields.put("operation", OP_REVISE_READING);
        fields.put("equipmentId", equipmentId);
        fields.put("readingId", readingId);
        fields.put("cumulativeMinutes", req.cumulativeMinutes());
        fields.put("expectedVersion", req.expectedVersion());
        return idempotency.execute(req.requestId(), OP_REVISE_READING, fingerprints.sha256(fields),
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    requireActiveEquipment(equipment);
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

    // ---------- 完成保养 ----------

    @Transactional
    public MaintenanceResponse completeMaintenance(String equipmentId, CompleteMaintenanceRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        var fields = new TreeMap<String, Object>();
        fields.put("operation", OP_COMPLETE_MAINTENANCE);
        fields.put("equipmentId", equipmentId);
        fields.put("readingId", req.readingId());
        fields.put("anchorRevisionNo", req.anchorRevisionNo());
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("operatorId", req.operatorId());
        return idempotency.execute(req.requestId(), OP_COMPLETE_MAINTENANCE, fingerprints.sha256(fields),
                MaintenanceResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    requireActiveEquipment(equipment);
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
                            req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
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
        Optional<IsolationOrder> activeOrder = repository.findActiveOrder(equipmentId);
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long runMinutes = latestCumulative - anchorCumulative;
        String status = runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK";
        return new StatusResponse(equipmentId, equipment.version(), equipment.maintenancePeriodMinutes(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                runMinutes, status,
                equipment.status(),
                activeOrder.map(IsolationOrder::orderId).orElse(null),
                activeOrder.map(IsolationOrder::status).orElse(null));
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

    // ---------- 内部规则 ----------

    private Equipment lockEquipment(String equipmentId) {
        return repository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
    }

    private ApiException equipmentNotFound(String equipmentId) {
        return ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在：" + equipmentId);
    }

    private void requireActiveEquipment(Equipment equipment) {
        if (!equipment.active()) {
            throw ApiException.unprocessable("EQUIPMENT_DEACTIVATED",
                    "设备已停用，不可提交工时读数或保养：" + equipment.equipmentId());
        }
    }

    /**
     * 生效隔离令裁决：EFFECTIVE 期间以独立事务固化被拒证据（锁定点集合快照、操作人、申报读数），
     * 随后抛 409；主事务回滚使读数、版本、幂等键均不落库。PENDING 尚未生效，不拦截。
     */
    private void rejectIfIsolated(Equipment equipment, AddReadingRequest req) {
        Optional<IsolationOrder> activeOrder = repository.findActiveOrder(equipment.equipmentId());
        if (activeOrder.isEmpty() || !activeOrder.get().isEffective()) {
            return;
        }
        IsolationOrder order = activeOrder.get();
        List<String> lockPointIds = repository.listLockPoints(order.orderId()).stream()
                .map(LockPoint::lockPointId)
                .sorted()
                .toList();
        Instant now = clock.instant();
        rejectedRecorder.record(new RejectedReading(
                0L,
                equipment.equipmentId(),
                order.orderId(),
                req.requestId(),
                req.operatorId(),
                req.readingId(),
                req.sampledAt(),
                req.cumulativeMinutes(),
                req.expectedVersion(),
                fingerprints.toJsonArray(lockPointIds),
                REJECTED_REASON,
                now));
        throw ApiException.conflict(REJECTED_REASON,
                "设备处于能源隔离生效期间，解除隔离前不得提交新增运行读数；隔离令 " + order.orderId());
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
