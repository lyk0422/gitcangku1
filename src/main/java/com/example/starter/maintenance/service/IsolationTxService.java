package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.BatchIsolationItemRequest;
import com.example.starter.maintenance.api.dto.BatchIsolationRequest;
import com.example.starter.maintenance.api.dto.BatchIsolationResponse;
import com.example.starter.maintenance.api.dto.ConfirmLockPointRequest;
import com.example.starter.maintenance.api.dto.CreateIsolationRequest;
import com.example.starter.maintenance.api.dto.DeactivateEquipmentRequest;
import com.example.starter.maintenance.api.dto.EquipmentDeactivationResponse;
import com.example.starter.maintenance.api.dto.FinalizeReleaseRequest;
import com.example.starter.maintenance.api.dto.IsolationDiagnosticsResponse;
import com.example.starter.maintenance.api.dto.IsolationEventView;
import com.example.starter.maintenance.api.dto.IsolationOrderResponse;
import com.example.starter.maintenance.api.dto.LockPointSpec;
import com.example.starter.maintenance.api.dto.LockPointView;
import com.example.starter.maintenance.api.dto.ReleaseLockPointRequest;
import com.example.starter.maintenance.api.dto.RejectedReadingView;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.IsolationEvent;
import com.example.starter.maintenance.domain.IsolationOrder;
import com.example.starter.maintenance.domain.LockPoint;
import com.example.starter.maintenance.domain.RejectedReading;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 能源隔离事务服务。
 *
 * <p>裁决规则：所有写操作先取设备行锁（同设备串行化，按提交顺序裁决），再做幂等判定、
 * 版本校验与业务规则；业务数据、锁定点操作人与审计事件在同一事务提交，任一失败整体回滚。
 * 批量隔离先完成整批完整后态校验，再在同一事务内落库，任一设备失败整批不生效。</p>
 *
 * <p>状态机：创建令 PENDING（版本+1）→ 每个锁定点经 ISOLATOR/VERIFIER 分别确认（不推进版本）
 * → 全部双角色确认，令 EFFECTIVE（版本+1）→ 锁定点逐一解除（不推进版本）
 * → 全部解除后整令解除 RELEASED（版本+1）。</p>
 */
@Service
public class IsolationTxService {

    public static final String OP_CREATE_ISOLATION = "CREATE_ISOLATION";
    public static final String OP_BATCH_CREATE_ISOLATION = "BATCH_CREATE_ISOLATION";
    public static final String OP_CONFIRM_LOCK_POINT = "CONFIRM_LOCK_POINT";
    public static final String OP_RELEASE_LOCK_POINT = "RELEASE_LOCK_POINT";
    public static final String OP_FINALIZE_RELEASE = "FINALIZE_RELEASE";
    public static final String OP_DEACTIVATE_EQUIPMENT = "DEACTIVATE_EQUIPMENT";

    public static final String EVT_CREATED = "CREATED";
    public static final String EVT_CONFIRMED = "CONFIRMED";
    public static final String EVT_EFFECTIVE = "EFFECTIVE";
    public static final String EVT_RELEASE_POINT = "RELEASE_POINT";
    public static final String EVT_RELEASED = "RELEASED";
    public static final String EVT_DEACTIVATED = "DEACTIVATED";

    private final EquipmentRepository repository;
    private final IdempotencyService idempotency;
    private final FingerprintService fingerprints;
    private final Clock clock;

    public IsolationTxService(EquipmentRepository repository, IdempotencyService idempotency,
                              FingerprintService fingerprints, Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.fingerprints = fingerprints;
        this.clock = clock;
    }

    // ---------- 创建隔离令（单设备） ----------

    @Transactional
    public IsolationOrderResponse createIsolation(String equipmentId, CreateIsolationRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        Map<String, Object> fields = new TreeMap<>();
        fields.put("operation", OP_CREATE_ISOLATION);
        fields.put("equipmentId", equipmentId);
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("operatorId", req.operatorId());
        fields.put("lockPoints", fingerprints.normalizeLockPoints(req.lockPoints()));
        return idempotency.execute(req.requestId(), OP_CREATE_ISOLATION, fingerprints.sha256(fields),
                IsolationOrderResponse.class, () -> {
                    requireActive(equipment);
                    checkVersion(equipment, req.expectedVersion());
                    validateLockPointSpecs(req.lockPoints());
                    if (repository.findActiveOrder(equipmentId).isPresent()) {
                        throw ApiException.unprocessable("ISOLATION_ORDER_ACTIVE",
                                "设备已存在未结束的能源隔离令，不可重复创建");
                    }
                    Instant now = clock.instant();
                    long orderId = repository.insertIsolationOrder(equipmentId, req.operatorId(),
                            req.requestId(), now);
                    insertLockPoints(orderId, req.lockPoints());
                    repository.insertEvent(orderId, equipmentId, EVT_CREATED, null, null,
                            req.operatorId(), req.requestId(), null, now);
                    repository.incrementVersion(equipmentId);
                    return buildOrderResponse(orderId);
                });
    }

    // ---------- 批量隔离 ----------

    @Transactional
    public BatchIsolationResponse createBatchIsolation(BatchIsolationRequest req) {
        // 先按设备标识字典序锁定整批设备行（多设备固定加锁顺序，避免死锁），
        // 再做幂等判定：并发同 requestId 的负方在行锁释放后可读到首次成功记录并重放快照。
        List<BatchIsolationItemRequest> lockOrder = req.items().stream()
                .sorted(java.util.Comparator.comparing(BatchIsolationItemRequest::equipmentId))
                .toList();
        List<Equipment> lockedEquipment = lockOrder.stream()
                .map(item -> lockEquipment(item.equipmentId()))
                .toList();
        Map<String, Equipment> equipmentById = new HashMap<>();
        for (int i = 0; i < lockOrder.size(); i++) {
            equipmentById.put(lockOrder.get(i).equipmentId(), lockedEquipment.get(i));
        }
        return idempotency.execute(req.requestId(), OP_BATCH_CREATE_ISOLATION,
                batchFingerprint(req, fingerprints), BatchIsolationResponse.class, () -> {
                    // 1) 结构校验：批次内设备不可重复；各设备锁定点集合标识唯一
                    Set<String> batchEquipment = new HashSet<>();
                    for (BatchIsolationItemRequest item : req.items()) {
                        if (!batchEquipment.add(item.equipmentId())) {
                            throw ApiException.unprocessable("BATCH_DUPLICATE_EQUIPMENT",
                                    "批量隔离中设备重复出现：" + item.equipmentId());
                        }
                        validateLockPointSpecs(item.lockPoints());
                    }
                    // 2) 完整后态校验（设备行已全部锁定）
                    for (BatchIsolationItemRequest item : req.items()) {
                        Equipment equipment = equipmentById.get(item.equipmentId());
                        requireActive(equipment);
                        checkVersion(equipment, item.expectedVersion());
                        if (repository.findActiveOrder(item.equipmentId()).isPresent()) {
                            throw ApiException.unprocessable("ISOLATION_ORDER_ACTIVE",
                                    "设备已存在未结束的能源隔离令，整批不生效：" + item.equipmentId());
                        }
                    }
                    // 3) 全部通过后在同一事务提交
                    Instant now = clock.instant();
                    List<IsolationOrderResponse> responses = new ArrayList<>();
                    for (BatchIsolationItemRequest item : req.items()) {
                        long orderId = repository.insertIsolationOrder(item.equipmentId(),
                                req.operatorId(), req.requestId(), now);
                        insertLockPoints(orderId, item.lockPoints());
                        repository.insertEvent(orderId, item.equipmentId(), EVT_CREATED, null, null,
                                req.operatorId(), req.requestId(), null, now);
                        repository.incrementVersion(item.equipmentId());
                        responses.add(buildOrderResponse(orderId));
                    }
                    return new BatchIsolationResponse(req.requestId(), req.operatorId(),
                            req.items().size(), responses);
                });
    }

    // ---------- 锁定点确认 ----------

    @Transactional
    public IsolationOrderResponse confirmLockPoint(String equipmentId, String lockPointId,
                                                   ConfirmLockPointRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        Map<String, Object> fields = new TreeMap<>();
        fields.put("operation", OP_CONFIRM_LOCK_POINT);
        fields.put("equipmentId", equipmentId);
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("operatorId", req.operatorId());
        fields.put("lockRole", req.lockRole());
        fields.put("lockPointId", lockPointId);
        return idempotency.execute(req.requestId(), OP_CONFIRM_LOCK_POINT, fingerprints.sha256(fields),
                IsolationOrderResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    IsolationOrder order = requireActiveOrder(equipmentId);
                    if (!order.isPending()) {
                        throw ApiException.unprocessable("ISOLATION_ALREADY_EFFECTIVE",
                                "隔离令已生效，全部锁定点已完成双角色确认");
                    }
                    LockPoint point = repository.findLockPointForUpdate(order.orderId(), lockPointId)
                            .orElseThrow(() -> ApiException.unprocessable("LOCK_POINT_NOT_FOUND",
                                    "锁定点不属于当前隔离令：" + lockPointId));
                    if (point.confirmedBy(req.lockRole())) {
                        throw ApiException.unprocessable("ROLE_ALREADY_CONFIRMED",
                                "同一维护角色对同一锁定点不可重复确认：" + req.lockRole() + " / "
                                        + lockPointId);
                    }
                    Instant now = clock.instant();
                    repository.confirmLockPoint(order.orderId(), lockPointId, req.lockRole(),
                            req.operatorId(), req.requestId(), now);
                    repository.insertEvent(order.orderId(), equipmentId, EVT_CONFIRMED, lockPointId,
                            req.lockRole(), req.operatorId(), req.requestId(), null, now);
                    List<LockPoint> allPoints = repository.listLockPoints(order.orderId());
                    if (allPoints.stream().allMatch(LockPoint::fullyConfirmed)) {
                        repository.markOrderEffective(order.orderId(), now);
                        repository.insertEvent(order.orderId(), equipmentId, EVT_EFFECTIVE, null, null,
                                req.operatorId(), req.requestId(), null, now);
                        repository.incrementVersion(equipmentId);
                    }
                    return buildOrderResponse(order.orderId());
                });
    }

    // ---------- 锁定点逐一解除 ----------

    @Transactional
    public IsolationOrderResponse releaseLockPoint(String equipmentId, String lockPointId,
                                                   ReleaseLockPointRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        Map<String, Object> fields = new TreeMap<>();
        fields.put("operation", OP_RELEASE_LOCK_POINT);
        fields.put("equipmentId", equipmentId);
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("operatorId", req.operatorId());
        fields.put("reason", req.reason());
        fields.put("lockPointId", lockPointId);
        return idempotency.execute(req.requestId(), OP_RELEASE_LOCK_POINT, fingerprints.sha256(fields),
                IsolationOrderResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    IsolationOrder order = requireActiveOrder(equipmentId);
                    if (!order.isEffective()) {
                        throw ApiException.unprocessable("ISOLATION_NOT_EFFECTIVE",
                                "隔离令尚未生效，不能解除锁定点");
                    }
                    LockPoint point = repository.findLockPointForUpdate(order.orderId(), lockPointId)
                            .orElseThrow(() -> ApiException.unprocessable("LOCK_POINT_NOT_FOUND",
                                    "锁定点不属于当前隔离令：" + lockPointId));
                    if (point.released()) {
                        throw ApiException.unprocessable("LOCK_POINT_ALREADY_RELEASED",
                                "锁定点已解除，不可重复解除：" + lockPointId);
                    }
                    Instant now = clock.instant();
                    repository.releaseLockPoint(order.orderId(), lockPointId, req.operatorId(),
                            req.requestId(), now);
                    repository.insertEvent(order.orderId(), equipmentId, EVT_RELEASE_POINT, lockPointId,
                            null, req.operatorId(), req.requestId(), req.reason(), now);
                    return buildOrderResponse(order.orderId());
                });
    }

    // ---------- 整令解除 ----------

    @Transactional
    public IsolationOrderResponse finalizeRelease(String equipmentId, FinalizeReleaseRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        Map<String, Object> fields = new TreeMap<>();
        fields.put("operation", OP_FINALIZE_RELEASE);
        fields.put("equipmentId", equipmentId);
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("operatorId", req.operatorId());
        fields.put("reason", req.reason());
        return idempotency.execute(req.requestId(), OP_FINALIZE_RELEASE, fingerprints.sha256(fields),
                IsolationOrderResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    IsolationOrder order = requireActiveOrder(equipmentId);
                    if (!order.isEffective()) {
                        throw ApiException.unprocessable("ISOLATION_NOT_EFFECTIVE",
                                "隔离令尚未生效，不能整令解除");
                    }
                    List<LockPoint> points = repository.listLockPoints(order.orderId());
                    long unreleased = points.stream().filter(point -> !point.released()).count();
                    if (unreleased > 0) {
                        throw ApiException.unprocessable("ISOLATION_PARTIALLY_RELEASED",
                                "仍有锁定点未逐一解除：已解除 " + (points.size() - unreleased)
                                        + " 个，要求 " + points.size() + " 个，差额 " + unreleased + " 个");
                    }
                    Instant now = clock.instant();
                    repository.markOrderReleased(order.orderId(), req.operatorId(), req.requestId(),
                            req.reason(), now);
                    repository.insertEvent(order.orderId(), equipmentId, EVT_RELEASED, null, null,
                            req.operatorId(), req.requestId(), req.reason(), now);
                    repository.incrementVersion(equipmentId);
                    return buildOrderResponse(order.orderId());
                });
    }

    // ---------- 设备停用 ----------

    @Transactional
    public EquipmentDeactivationResponse deactivateEquipment(String equipmentId,
                                                             DeactivateEquipmentRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        Map<String, Object> fields = new TreeMap<>();
        fields.put("operation", OP_DEACTIVATE_EQUIPMENT);
        fields.put("equipmentId", equipmentId);
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("operatorId", req.operatorId());
        return idempotency.execute(req.requestId(), OP_DEACTIVATE_EQUIPMENT, fingerprints.sha256(fields),
                EquipmentDeactivationResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    if (!equipment.active()) {
                        throw ApiException.unprocessable("EQUIPMENT_ALREADY_DEACTIVATED",
                                "设备已停用，不可重复停用：" + equipmentId);
                    }
                    if (repository.findActiveOrder(equipmentId).isPresent()) {
                        throw ApiException.unprocessable("ACTIVE_ISOLATION_PREVENTS_DEACTIVATION",
                                "设备存在未结束的能源隔离令，不可停用；须先完整解除隔离");
                    }
                    Instant now = clock.instant();
                    repository.deactivateEquipment(equipmentId, now);
                    repository.insertEvent(null, equipmentId, EVT_DEACTIVATED, null, null,
                            req.operatorId(), req.requestId(), null, now);
                    repository.incrementVersion(equipmentId);
                    return new EquipmentDeactivationResponse(equipmentId, Equipment.DEACTIVATED,
                            now, equipment.version() + 1);
                });
    }

    // ---------- 查询（不改状态） ----------

    @Transactional(readOnly = true)
    public IsolationOrderResponse getOrder(String equipmentId, long orderId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        IsolationOrder order = repository.findIsolationOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("ISOLATION_ORDER_NOT_FOUND",
                        "隔离令不存在：" + orderId));
        if (!order.equipmentId().equals(equipmentId)) {
            throw ApiException.notFound("ISOLATION_ORDER_NOT_FOUND",
                    "隔离令不属于该设备：" + orderId);
        }
        return buildOrderResponse(orderId);
    }

    @Transactional(readOnly = true)
    public List<IsolationOrderResponse> listOrders(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listOrders(equipmentId).stream()
                .map(order -> buildOrderResponse(order.orderId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<IsolationEventView> listEvents(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listEventsByEquipment(equipmentId).stream()
                .map(IsolationTxService::toEventView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RejectedReadingView> listRejectedReadings(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listRejectedReadings(equipmentId).stream()
                .map(IsolationTxService::toRejectedView)
                .toList();
    }

    /**
     * 诊断视图：实际值/要求值/差额与比例；时间区间左闭右开 [windowFrom, windowTo)，
     * 任一端点为 null 表示该侧不限制。查询只读，不产生任何状态变更。
     */
    @Transactional(readOnly = true)
    public IsolationDiagnosticsResponse diagnostics(String equipmentId, Instant windowFrom,
                                                    Instant windowTo) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        IsolationOrder activeOrder = repository.findActiveOrder(equipmentId).orElse(null);
        int required = 0;
        int confirmed = 0;
        int released = 0;
        long effectiveDurationSeconds = 0L;
        Long currentOrderId = null;
        String orderStatus = null;
        if (activeOrder != null) {
            currentOrderId = activeOrder.orderId();
            orderStatus = activeOrder.status();
            List<LockPoint> points = repository.listLockPoints(activeOrder.orderId());
            required = points.size();
            confirmed = (int) points.stream().filter(LockPoint::fullyConfirmed).count();
            released = (int) points.stream().filter(LockPoint::released).count();
            if (activeOrder.isEffective() && activeOrder.effectiveAt() != null) {
                effectiveDurationSeconds = clock.instant().getEpochSecond()
                        - activeOrder.effectiveAt().getEpochSecond();
            }
        } else {
            // 无活动令时，最近一条已解除且曾生效的历史令给出实际生效时长
            effectiveDurationSeconds = repository.findLatestOrder(equipmentId)
                    .filter(order -> IsolationOrder.RELEASED.equals(order.status()))
                    .filter(order -> order.effectiveAt() != null && order.releasedAt() != null)
                    .map(order -> order.releasedAt().getEpochSecond() - order.effectiveAt().getEpochSecond())
                    .orElse(0L);
        }
        List<IsolationEventView> events = repository.listEventsByEquipment(equipmentId).stream()
                .filter(event -> inWindow(event.eventAt(), windowFrom, windowTo))
                .map(IsolationTxService::toEventView)
                .toList();
        List<RejectedReadingView> rejected = repository.listRejectedReadings(equipmentId).stream()
                .filter(row -> inWindow(row.rejectedAt(), windowFrom, windowTo))
                .map(IsolationTxService::toRejectedView)
                .toList();
        return new IsolationDiagnosticsResponse(
                equipmentId,
                equipment.version(),
                equipment.status(),
                currentOrderId,
                orderStatus,
                required,
                confirmed,
                required - confirmed,
                released,
                required - released,
                required == 0 ? 0d : (double) confirmed / required,
                required == 0 ? 0d : (double) released / required,
                effectiveDurationSeconds,
                windowFrom,
                windowTo,
                events.size(),
                rejected.size(),
                events,
                rejected);
    }

    // ---------- 内部规则与组装 ----------

    /** 锁定点申报集合校验：非空、令内标识唯一（重复返回 422）。 */
    private void validateLockPointSpecs(List<LockPointSpec> lockPoints) {
        Set<String> seen = new HashSet<>();
        for (LockPointSpec point : lockPoints) {
            if (!seen.add(point.lockPointId())) {
                throw ApiException.unprocessable("LOCK_POINT_DUPLICATE",
                        "同一隔离令内锁定点标识重复：" + point.lockPointId());
            }
        }
    }

    private void insertLockPoints(long orderId, List<LockPointSpec> lockPoints) {
        for (int i = 0; i < lockPoints.size(); i++) {
            LockPointSpec point = lockPoints.get(i);
            repository.insertLockPoint(orderId, i, point.lockPointId(), point.description());
        }
    }

    /** 暴露指纹服务供外观层并发唯一键冲突补偿时计算与事务内一致的指纹。 */
    public FingerprintService fingerprints() {
        return fingerprints;
    }

    /** 批量隔离请求指纹（事务内与外观补偿共用，保证两边判定一致）。 */
    public static String batchFingerprint(BatchIsolationRequest req, FingerprintService fp) {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("operation", OP_BATCH_CREATE_ISOLATION);
        fields.put("operatorId", req.operatorId());
        fields.put("items", req.items().stream()
                .sorted(Comparator.comparing(BatchIsolationItemRequest::equipmentId))
                .map(item -> {
                    Map<String, Object> entry = new TreeMap<>();
                    entry.put("equipmentId", item.equipmentId());
                    entry.put("expectedVersion", item.expectedVersion());
                    entry.put("lockPoints", fp.normalizeLockPoints(item.lockPoints()));
                    return entry;
                })
                .toList());
        return fp.sha256(fields);
    }

    private IsolationOrderResponse buildOrderResponse(long orderId) {
        IsolationOrder order = repository.findIsolationOrder(orderId)
                .orElseThrow(() -> new IllegalStateException("隔离令缺失：" + orderId));
        List<LockPoint> points = repository.listLockPoints(orderId);
        List<LockPointView> views = points.stream()
                .map(IsolationTxService::toLockPointView)
                .toList();
        int confirmed = (int) points.stream().filter(LockPoint::fullyConfirmed).count();
        int released = (int) points.stream().filter(LockPoint::released).count();
        long equipmentVersion = repository.findEquipment(order.equipmentId())
                .orElseThrow(() -> equipmentNotFound(order.equipmentId())).version();
        return new IsolationOrderResponse(
                order.orderId(),
                order.equipmentId(),
                order.status(),
                order.createdBy(),
                order.createdAt(),
                order.effectiveAt(),
                order.releasedAt(),
                order.releasedBy(),
                order.releaseReason(),
                equipmentVersion,
                points.size(),
                confirmed,
                released,
                views);
    }

    private static LockPointView toLockPointView(LockPoint point) {
        String state;
        if (point.released()) {
            state = "RELEASED";
        } else if (point.fullyConfirmed()) {
            state = "CONFIRMED";
        } else if (point.confirmedIsolatorBy() != null || point.confirmedVerifierBy() != null) {
            state = "PARTIALLY_CONFIRMED";
        } else {
            state = "PENDING";
        }
        return new LockPointView(
                point.orderId(),
                point.ordinal(),
                point.lockPointId(),
                point.description(),
                state,
                point.confirmedIsolatorBy(),
                point.confirmedIsolatorAt(),
                point.confirmedVerifierBy(),
                point.confirmedVerifierAt(),
                point.releasedBy(),
                point.releasedAt());
    }

    private static IsolationEventView toEventView(IsolationEvent event) {
        return new IsolationEventView(
                event.eventId(),
                event.orderId(),
                event.equipmentId(),
                event.eventType(),
                event.lockPointId(),
                event.lockRole(),
                event.operatorId(),
                event.requestId(),
                event.detail(),
                event.eventAt());
    }

    private static RejectedReadingView toRejectedView(RejectedReading row) {
        return new RejectedReadingView(
                row.rejectedId(),
                row.equipmentId(),
                row.orderId(),
                row.requestId(),
                row.operatorId(),
                row.readingId(),
                row.sampledAt(),
                row.cumulativeMinutes(),
                row.expectedVersion(),
                row.lockPointIds(),
                row.reasonCode(),
                row.rejectedAt());
    }

    /** 左闭右开区间判定：[from, to)，null 端表示不限制。 */
    private static boolean inWindow(Instant instant, Instant from, Instant to) {
        return (from == null || !instant.isBefore(from)) && (to == null || instant.isBefore(to));
    }

    private Equipment lockEquipment(String equipmentId) {
        return repository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
    }

    private IsolationOrder requireActiveOrder(String equipmentId) {
        return repository.findActiveOrder(equipmentId)
                .orElseThrow(() -> ApiException.unprocessable("NO_ACTIVE_ISOLATION",
                        "设备没有待确认或生效中的能源隔离令"));
    }

    private void requireActive(Equipment equipment) {
        if (!equipment.active()) {
            throw ApiException.unprocessable("EQUIPMENT_DEACTIVATED",
                    "设备已停用，不可执行能源隔离操作：" + equipment.equipmentId());
        }
    }

    private void checkVersion(Equipment equipment, long expectedVersion) {
        if (equipment.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "设备版本冲突：期望 " + expectedVersion + "，当前 " + equipment.version());
        }
    }

    private ApiException equipmentNotFound(String equipmentId) {
        return ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在：" + equipmentId);
    }
}
