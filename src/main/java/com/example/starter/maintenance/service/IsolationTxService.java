package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.BatchIsolationItem;
import com.example.starter.maintenance.api.dto.BatchIsolationRequest;
import com.example.starter.maintenance.api.dto.BatchIsolationResponse;
import com.example.starter.maintenance.api.dto.ConfirmationView;
import com.example.starter.maintenance.api.dto.ConfirmIsolationRequest;
import com.example.starter.maintenance.api.dto.CreateIsolationRequest;
import com.example.starter.maintenance.api.dto.DecommissionEquipmentRequest;
import com.example.starter.maintenance.api.dto.IsolationResponse;
import com.example.starter.maintenance.api.dto.LockPointInput;
import com.example.starter.maintenance.api.dto.LockPointView;
import com.example.starter.maintenance.api.dto.RejectedReadingView;
import com.example.starter.maintenance.api.dto.ReleaseLockPointRequest;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.EquipmentStates;
import com.example.starter.maintenance.domain.IsolationConfirmation;
import com.example.starter.maintenance.domain.IsolationLockPoint;
import com.example.starter.maintenance.domain.IsolationOrder;
import com.example.starter.maintenance.domain.IsolationStates;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备能源隔离事务服务。隔离令生命周期：PENDING（创建，待双角色确认）→ ACTIVE（两名不同角色确认后生效，
 * 生效区间左闭右开 [effectiveAt, releasedAt)）→ RELEASED（全部锁定点逐一解除并写原因）。
 * 写操作流程：设备行锁（批量按设备标识排序加锁）→ 幂等判定 → 版本与状态校验 → 业务规则 → 变更并版本加一。
 */
@Service
public class IsolationTxService {

    /** 隔离生效要求的不同维护角色确认数。 */
    public static final int REQUIRED_ROLE_COUNT = 2;

    private final EquipmentRepository repository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public IsolationTxService(EquipmentRepository repository, IdempotencyService idempotency, Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------- 创建隔离令 ----------

    @Transactional
    public IsolationResponse createIsolation(String equipmentId, CreateIsolationRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = FingerprintUtil.canonical(Map.of(
                "equipmentId", equipmentId,
                "expectedVersion", req.expectedVersion(),
                "operatorId", req.operatorId(),
                "lockPoints", normalizedLockPoints(req.lockPoints())));
        return idempotency.execute(req.requestId(), "CREATE_ISOLATION", fingerprint,
                IsolationResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    requireActiveEquipment(equipment);
                    requireNoOpenIsolation(equipmentId);
                    List<LockPointInput> points = requireDistinctLockPoints(req.lockPoints());
                    Instant now = clock.instant();
                    long isolationId = repository.insertIsolationOrder(new IsolationOrder(
                            0L, equipmentId, IsolationStates.PENDING, req.operatorId(), now,
                            null, null, null, null));
                    insertLockPoints(isolationId, points, now);
                    repository.incrementVersion(equipmentId);
                    return loadView(isolationId, equipment.version() + 1);
                });
    }

    // ---------- 批量隔离 ----------

    @Transactional
    public BatchIsolationResponse batchCreateIsolations(BatchIsolationRequest req) {
        List<String> equipmentIds = req.items().stream().map(BatchIsolationItem::equipmentId).toList();
        Set<String> uniqueEquipmentIds = new HashSet<>(equipmentIds);
        if (uniqueEquipmentIds.size() != equipmentIds.size()) {
            throw ApiException.unprocessable("BATCH_DUPLICATE_EQUIPMENT",
                    "批量隔离请求中同一设备只允许出现一次");
        }
        String fingerprint = FingerprintUtil.canonical(Map.of(
                "operatorId", req.operatorId(),
                "items", req.items().stream().map(item -> Map.of(
                        "equipmentId", item.equipmentId(),
                        "expectedVersion", item.expectedVersion(),
                        "lockPoints", normalizedLockPoints(item.lockPoints()))).toList()));
        return idempotency.execute(req.requestId(), "BATCH_ISOLATE", fingerprint,
                BatchIsolationResponse.class, () -> {
                    // 先按设备标识排序统一加锁，再校验完整后态；任一失败整批回滚，不留部分结果。
                    List<Equipment> locked = repository.lockEquipmentsInIdOrder(equipmentIds);
                    Map<String, Equipment> equipmentMap = new HashMap<>();
                    for (Equipment equipment : locked) {
                        equipmentMap.put(equipment.equipmentId(), equipment);
                    }
                    List<ValidatedItem> validated = new ArrayList<>();
                    for (BatchIsolationItem item : req.items()) {
                        Equipment equipment = Optional.ofNullable(equipmentMap.get(item.equipmentId()))
                                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND",
                                        "设备不存在：" + item.equipmentId()));
                        checkVersion(equipment, item.expectedVersion());
                        requireActiveEquipment(equipment);
                        requireNoOpenIsolation(item.equipmentId());
                        validated.add(new ValidatedItem(equipment, item,
                                requireDistinctLockPoints(item.lockPoints())));
                    }
                    // 全部校验通过后一次性提交所有隔离令。
                    Instant now = clock.instant();
                    List<IsolationResponse> views = new ArrayList<>();
                    for (ValidatedItem item : validated) {
                        long isolationId = repository.insertIsolationOrder(new IsolationOrder(
                                0L, item.equipment.equipmentId(), IsolationStates.PENDING,
                                req.operatorId(), now, null, null, null, null));
                        insertLockPoints(isolationId, item.points, now);
                        repository.incrementVersion(item.equipment.equipmentId());
                        views.add(loadView(isolationId, item.equipment.version() + 1));
                    }
                    return new BatchIsolationResponse(req.requestId(), views);
                });
    }

    // ---------- 双角色确认 ----------

    @Transactional
    public IsolationResponse confirmIsolation(String equipmentId, long isolationId,
                                              ConfirmIsolationRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = FingerprintUtil.canonical(Map.of(
                "equipmentId", equipmentId,
                "isolationId", isolationId,
                "expectedVersion", req.expectedVersion(),
                "operatorId", req.operatorId(),
                "role", req.role()));
        return idempotency.execute(req.requestId(), "CONFIRM_ISOLATION", fingerprint,
                IsolationResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    requireActiveEquipment(equipment);
                    IsolationOrder order = loadIsolationOfEquipment(equipmentId, isolationId);
                    if (IsolationStates.RELEASED.equals(order.state())) {
                        throw ApiException.unprocessable("ISOLATION_ALREADY_RELEASED",
                                "隔离令已全部解除，不可再确认：" + isolationId);
                    }
                    if (IsolationStates.ACTIVE.equals(order.state())) {
                        throw ApiException.unprocessable("ISOLATION_ALREADY_ACTIVE",
                                "隔离令已有两名角色确认并生效，不可重复确认：" + isolationId);
                    }
                    List<IsolationConfirmation> confirmations = repository.listConfirmations(isolationId);
                    if (confirmations.stream().anyMatch(c -> c.role().equals(req.role()))) {
                        throw ApiException.unprocessable("ROLE_ALREADY_CONFIRMED",
                                "同一维护角色不可二次确认锁定点，角色：" + req.role());
                    }
                    if (confirmations.size() >= REQUIRED_ROLE_COUNT) {
                        throw ApiException.unprocessable("ISOLATION_ALREADY_ACTIVE",
                                "隔离令确认角色数已满：" + isolationId);
                    }
                    Instant now = clock.instant();
                    try {
                        repository.insertConfirmation(new IsolationConfirmation(
                                0L, isolationId, req.operatorId(), req.role(), req.requestId(),
                                confirmations.size() + 1, now));
                    } catch (org.springframework.dao.DuplicateKeyException e) {
                        // 并发下两名同角色确认同时通过前置检查时，由唯一约束兜底为 422（事务回滚）。
                        throw ApiException.unprocessable("ROLE_ALREADY_CONFIRMED",
                                "同一维护角色不可二次确认锁定点，角色：" + req.role());
                    }
                    if (confirmations.size() + 1 == REQUIRED_ROLE_COUNT) {
                        repository.activateIsolation(isolationId, now);
                    }
                    repository.incrementVersion(equipmentId);
                    return loadView(isolationId, equipment.version() + 1);
                });
    }

    // ---------- 逐一解除锁定点 ----------

    @Transactional
    public IsolationResponse releaseLockPoint(String equipmentId, long isolationId, String lockPointId,
                                              ReleaseLockPointRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = FingerprintUtil.canonical(Map.of(
                "equipmentId", equipmentId,
                "isolationId", isolationId,
                "lockPointId", lockPointId,
                "expectedVersion", req.expectedVersion(),
                "operatorId", req.operatorId(),
                "reason", req.reason()));
        return idempotency.execute(req.requestId(), "RELEASE_LOCK_POINT", fingerprint,
                IsolationResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    requireActiveEquipment(equipment);
                    IsolationOrder order = loadIsolationOfEquipment(equipmentId, isolationId);
                    if (IsolationStates.PENDING.equals(order.state())) {
                        throw ApiException.unprocessable("ISOLATION_NOT_ACTIVE",
                                "隔离令尚未经两名角色确认生效，不可解除：" + isolationId);
                    }
                    if (IsolationStates.RELEASED.equals(order.state())) {
                        throw ApiException.unprocessable("ISOLATION_ALREADY_RELEASED",
                                "隔离令已全部解除，不可重复解除：" + isolationId);
                    }
                    IsolationLockPoint point = repository.findLockPoint(isolationId, lockPointId)
                            .orElseThrow(() -> ApiException.notFound("LOCK_POINT_NOT_FOUND",
                                    "锁定点不存在：" + lockPointId));
                    if (point.releasedAt() != null) {
                        throw ApiException.unprocessable("LOCK_POINT_ALREADY_RELEASED",
                                "锁定点已解除，不可重复解除（不允许批量/部分解除）：" + lockPointId);
                    }
                    Instant now = clock.instant();
                    int updated = repository.releaseLockPoint(isolationId, lockPointId,
                            req.reason(), req.operatorId(), now);
                    if (updated == 0) {
                        throw ApiException.unprocessable("LOCK_POINT_ALREADY_RELEASED",
                                "锁定点已被其他操作解除：" + lockPointId);
                    }
                    List<IsolationLockPoint> points = repository.listLockPoints(isolationId);
                    long remaining = points.stream().filter(p -> p.releasedAt() == null).count();
                    if (remaining == 0) {
                        // 全部逐一解除后固化各点原因快照并收尾隔离令。
                        List<Map<String, Object>> reasons = points.stream()
                                .map(p -> {
                                    Map<String, Object> entry = new HashMap<>();
                                    entry.put("lockPointId", p.lockPointId());
                                    entry.put("reason", p.releaseReason());
                                    entry.put("releasedBy", p.releasedBy());
                                    return entry;
                                })
                                .toList();
                        repository.markIsolationReleased(isolationId, FingerprintUtil.canonical(reasons),
                                req.operatorId(), now);
                    }
                    repository.incrementVersion(equipmentId);
                    return loadView(isolationId, equipment.version() + 1);
                });
    }

    // ---------- 停用设备 ----------

    @Transactional
    public com.example.starter.maintenance.api.dto.EquipmentResponse decommission(
            String equipmentId, DecommissionEquipmentRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = FingerprintUtil.canonical(Map.of(
                "equipmentId", equipmentId,
                "expectedVersion", req.expectedVersion(),
                "operatorId", req.operatorId()));
        return idempotency.execute(req.requestId(), "DECOMMISSION_EQUIPMENT", fingerprint,
                com.example.starter.maintenance.api.dto.EquipmentResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    if (EquipmentStates.DECOMMISSIONED.equals(equipment.state())) {
                        throw ApiException.unprocessable("EQUIPMENT_DECOMMISSIONED",
                                "设备已停用：" + equipmentId);
                    }
                    if (repository.findOpenIsolationOrder(equipmentId).isPresent()) {
                        throw ApiException.unprocessable("ISOLATION_STILL_OPEN",
                                "设备存在未完成的能源隔离令，不可停用：" + equipmentId);
                    }
                    repository.decommissionEquipment(equipmentId, clock.instant());
                    repository.incrementVersion(equipmentId);
                    return new com.example.starter.maintenance.api.dto.EquipmentResponse(
                            equipmentId, equipment.maintenancePeriodMinutes(),
                            equipment.version() + 1, EquipmentStates.DECOMMISSIONED);
                });
    }

    // ---------- 查询（不改状态） ----------

    @Transactional(readOnly = true)
    public IsolationResponse getIsolation(String equipmentId, long isolationId) {
        repository.findEquipment(equipmentId)
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在：" + equipmentId));
        loadIsolationOfEquipment(equipmentId, isolationId);
        return loadView(isolationId, 0L);
    }

    @Transactional(readOnly = true)
    public List<IsolationResponse> listIsolations(String equipmentId) {
        repository.findEquipment(equipmentId)
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在：" + equipmentId));
        return repository.listIsolationOrders(equipmentId).stream()
                .map(order -> loadView(order.isolationId(), 0L))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RejectedReadingView> listRejectedReadings(String equipmentId) {
        repository.findEquipment(equipmentId)
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在：" + equipmentId));
        return repository.listRejectedReadings(equipmentId).stream()
                .map(row -> new RejectedReadingView(row.rejectedId(), row.equipmentId(), row.isolationId(),
                        row.readingId(), row.sampledAt(), row.cumulativeMinutes(), row.operatorId(),
                        row.requestId(), row.lockPointsJson(), row.rejectedAt()))
                .toList();
    }

    // ---------- 内部规则与装配 ----------

    private record ValidatedItem(Equipment equipment, BatchIsolationItem item, List<LockPointInput> points) {
    }

    private Equipment lockEquipment(String equipmentId) {
        return repository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在：" + equipmentId));
    }

    private void checkVersion(Equipment equipment, long expectedVersion) {
        if (equipment.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "设备版本冲突：期望 " + expectedVersion + "，当前 " + equipment.version());
        }
    }

    private void requireActiveEquipment(Equipment equipment) {
        if (EquipmentStates.DECOMMISSIONED.equals(equipment.state())) {
            throw ApiException.unprocessable("EQUIPMENT_DECOMMISSIONED",
                    "设备已停用，不可执行该操作：" + equipment.equipmentId());
        }
    }

    private void requireNoOpenIsolation(String equipmentId) {
        if (repository.findOpenIsolationOrder(equipmentId).isPresent()) {
            throw ApiException.unprocessable("ISOLATION_ALREADY_OPEN",
                    "设备已存在待确认或生效中的隔离令：" + equipmentId);
        }
    }

    private IsolationOrder loadIsolationOfEquipment(String equipmentId, long isolationId) {
        IsolationOrder order = repository.findIsolationOrder(isolationId)
                .orElseThrow(() -> ApiException.notFound("ISOLATION_NOT_FOUND",
                        "隔离令不存在：" + isolationId));
        if (!order.equipmentId().equals(equipmentId)) {
            throw ApiException.notFound("ISOLATION_NOT_FOUND",
                    "隔离令不属于该设备：" + isolationId);
        }
        return order;
    }

    /** 锁定点标识去重（422），保留请求顺序作为固化顺序。 */
    private List<LockPointInput> requireDistinctLockPoints(List<LockPointInput> points) {
        Set<String> seen = new HashSet<>();
        for (LockPointInput point : points) {
            if (!seen.add(point.lockPointId())) {
                throw ApiException.unprocessable("LOCK_POINT_DUPLICATE",
                        "同一隔离令内锁定点标识重复：" + point.lockPointId());
            }
        }
        return points;
    }

    private List<Map<String, String>> normalizedLockPoints(List<LockPointInput> points) {
        return points.stream()
                .map(point -> {
                    // label 可空，使用允许 null 值的 HashMap，保留空值语义。
                    Map<String, String> entry = new HashMap<>();
                    entry.put("lockPointId", point.lockPointId());
                    entry.put("label", point.label());
                    return entry;
                })
                .toList();
    }

    private void insertLockPoints(long isolationId, List<LockPointInput> points, Instant lockedAt) {
        int seqNo = 1;
        for (LockPointInput point : points) {
            repository.insertLockPoint(isolationId, point.lockPointId(), point.label(), seqNo++, lockedAt);
        }
    }

    private IsolationResponse loadView(long isolationId, long equipmentVersion) {
        IsolationOrder order = repository.findIsolationOrder(isolationId)
                .orElseThrow(() -> ApiException.notFound("ISOLATION_NOT_FOUND",
                        "隔离令不存在：" + isolationId));
        List<IsolationLockPoint> points = repository.listLockPoints(isolationId);
        List<IsolationConfirmation> confirmations = repository.listConfirmations(isolationId);
        int releasedCount = (int) points.stream().filter(p -> p.releasedAt() != null).count();
        List<LockPointView> pointViews = points.stream()
                .map(p -> new LockPointView(p.lockPointId(), p.label(), p.seqNo(), p.releaseReason(),
                        p.lockedAt(), p.releasedAt(), p.releasedBy()))
                .toList();
        List<ConfirmationView> confirmationViews = confirmations.stream()
                .map(c -> new ConfirmationView(c.seqNo(), c.operatorId(), c.role(),
                        c.requestId(), c.confirmedAt()))
                .toList();
        Long effectiveDurationSeconds = null;
        if (order.effectiveAt() != null) {
            // 生效区间左闭右开：已解除取 [effectiveAt, releasedAt)，生效中取至当前时刻。
            Instant intervalEnd = order.releasedAt() != null ? order.releasedAt() : clock.instant();
            effectiveDurationSeconds = Duration.between(order.effectiveAt(), intervalEnd).getSeconds();
        }
        return new IsolationResponse(
                order.isolationId(),
                order.equipmentId(),
                order.state(),
                order.createdBy(),
                order.createdAt(),
                order.effectiveAt(),
                order.releasedAt(),
                order.releaseReason(),
                order.releasedBy(),
                confirmations.size(),
                REQUIRED_ROLE_COUNT,
                points.size(),
                releasedCount,
                points.size() - releasedCount,
                effectiveDurationSeconds,
                pointViews,
                confirmationViews,
                equipmentVersion);
    }
}
