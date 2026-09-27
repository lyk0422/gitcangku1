package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.BatchCreateIsolationRequest;
import com.example.starter.maintenance.api.dto.BatchIsolationItem;
import com.example.starter.maintenance.api.dto.BatchIsolationItemResponse;
import com.example.starter.maintenance.api.dto.BatchIsolationResponse;
import com.example.starter.maintenance.api.dto.ConfirmationView;
import com.example.starter.maintenance.api.dto.ConfirmLockPointRequest;
import com.example.starter.maintenance.api.dto.CreateIsolationRequest;
import com.example.starter.maintenance.api.dto.IsolationHistoryEvent;
import com.example.starter.maintenance.api.dto.IsolationOrderResponse;
import com.example.starter.maintenance.api.dto.IsolatedReadingRejectionView;
import com.example.starter.maintenance.api.dto.LockPointDetail;
import com.example.starter.maintenance.api.dto.LockPointInput;
import com.example.starter.maintenance.api.dto.LockReleaseInput;
import com.example.starter.maintenance.api.dto.ReleaseIsolationRequest;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.IsolationConfirmation;
import com.example.starter.maintenance.domain.IsolationLockPoint;
import com.example.starter.maintenance.domain.IsolationOrder;
import com.example.starter.maintenance.store.EquipmentRepository;
import com.example.starter.maintenance.store.IsolationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 设备能源隔离事务服务。
 *
 * <p>状态机：PENDING（创建，待确认）→ ACTIVE（每个锁定点集齐两名不同角色确认，隔离生效）
 * → RELEASED（全部锁定点逐一解除并填写原因）。
 *
 * <p>隔离生效（ACTIVE）期间设备不得新增运行读数（返回 409 并在独立事务固化拒绝证据），
 * 保养检查项可继续提交；解除前读数一律拒绝。所有写操作在单事务内先完成完整最终状态校验再提交，
 * 任一失败整批回滚、查不到半成品。
 */
@Service
public class IsolationTxService {

    /** 每个锁定点要求的不同维护角色确认数。 */
    public static final int REQUIRED_ROLE_COUNT = 2;

    private final IsolationRepository repository;
    private final EquipmentRepository equipmentRepository;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IsolationTxService(IsolationRepository repository, EquipmentRepository equipmentRepository,
                              IdempotencyService idempotency, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.equipmentRepository = equipmentRepository;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 创建隔离令（单设备） ----------

    @Transactional
    public IsolationOrderResponse createIsolation(String equipmentId, CreateIsolationRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = createFingerprint(equipmentId, req);
        return idempotency.execute(req.requestId(), "CREATE_ISOLATION", fingerprint,
                IsolationOrderResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    assertDistinctLockPoints(req.lockPoints());
                    assertOrderIdAvailable(req.orderId());
                    if (repository.existsOpenOrder(equipmentId)) {
                        throw ApiException.unprocessable("EQUIPMENT_ALREADY_ISOLATED",
                                "设备存在未解除的隔离令，不可重复隔离：" + equipmentId);
                    }
                    Instant now = clock.instant();
                    persistOrder(equipmentId, req.orderId(), req.createdBy(), req.requestId(), now,
                            req.lockPoints());
                    equipmentRepository.incrementVersion(equipmentId);
                    return assemble(repository.findOrder(req.orderId()).orElseThrow(), equipment.version() + 1);
                });
    }

    // ---------- 批量隔离多设备 ----------

    @Transactional
    public BatchIsolationResponse createBatch(BatchCreateIsolationRequest req) {
        // 规范化指纹：操作者 + 操作类型 + 规范化（排序）对象集合 + 全部影响结果的字段。
        String fingerprint = batchFingerprint(req);
        return idempotency.execute(req.requestId(), "BATCH_CREATE_ISOLATION", fingerprint,
                BatchIsolationResponse.class, () -> {
                    // 先校验请求内集合自身（设备、隔离令不得在批内重复）；
                    // 再按设备标识排序加锁（避免并发批间死锁），逐台做完整最终状态校验。
                    assertBatchItemsDistinct(req);
                    List<BatchIsolationItem> lockOrder = req.orders().stream()
                            .sorted(java.util.Comparator.comparing(BatchIsolationItem::equipmentId))
                            .toList();
                    for (BatchIsolationItem item : lockOrder) {
                        assertDistinctLockPoints(item.lockPoints());
                        assertOrderIdAvailable(item.orderId());
                        Equipment equipment = lockEquipment(item.equipmentId());
                        checkVersion(equipment, item.expectedVersion());
                        if (repository.existsOpenOrder(item.equipmentId())) {
                            throw ApiException.unprocessable("EQUIPMENT_ALREADY_ISOLATED",
                                    "设备存在未解除的隔离令，不可重复隔离：" + item.equipmentId());
                        }
                    }
                    // 全部校验通过后在同一事务提交；响应按请求提交顺序稳定排序。
                    Instant now = clock.instant();
                    List<BatchIsolationItemResponse> views = new ArrayList<>();
                    for (BatchIsolationItem item : req.orders()) {
                        persistOrder(item.equipmentId(), item.orderId(), req.createdBy(),
                                req.requestId(), now, item.lockPoints());
                        equipmentRepository.incrementVersion(item.equipmentId());
                        views.add(new BatchIsolationItemResponse(item.equipmentId(), item.orderId(),
                                IsolationOrder.PENDING, item.lockPoints().size()));
                    }
                    return new BatchIsolationResponse(req.requestId(), req.createdBy(), now,
                            views.size(), views.size(), views);
                });
    }

    // ---------- 确认锁定点 ----------

    @Transactional
    public IsolationOrderResponse confirmLockPoint(String orderId, ConfirmLockPointRequest req) {
        IsolationOrder lockedOrder = lockOrder(orderId);
        String fingerprint = IsolationFingerprint.of(objectMapper, req.operatorId(),
                "CONFIRM_LOCK_POINT", objectMapper.createObjectNode()
                        .put("orderId", orderId)
                        .put("lockPointId", req.lockPointId())
                        .put("roleId", req.roleId()));
        return idempotency.execute(req.requestId(), "CONFIRM_LOCK_POINT", fingerprint,
                IsolationOrderResponse.class, () -> {
                    Equipment equipment = equipmentRepository.findEquipmentForUpdate(lockedOrder.equipmentId())
                            .orElseThrow(() -> equipmentNotFound(lockedOrder.equipmentId()));
                    if (!IsolationOrder.PENDING.equals(lockedOrder.status())) {
                        throw ApiException.unprocessable("ISOLATION_NOT_PENDING",
                                "仅待确认（PENDING）隔离令可确认锁定点，当前状态 "
                                        + lockedOrder.status() + "；隔离令 " + orderId);
                    }
                    List<IsolationLockPoint> lockPoints = repository.listLockPoints(orderId);
                    IsolationLockPoint target = lockPoints.stream()
                            .filter(lp -> lp.lockPointId().equals(req.lockPointId()))
                            .findFirst()
                            .orElseThrow(() -> ApiException.notFound("LOCK_POINT_NOT_FOUND",
                                    "锁定点不属于该隔离令或不存在：" + req.lockPointId()));
                    // 同一角色对同一锁定点双确认 → 422
                    Set<String> confirmedRoles = repository.listConfirmations(orderId).stream()
                            .filter(c -> c.lockPointId().equals(req.lockPointId()))
                            .map(IsolationConfirmation::roleId)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (confirmedRoles.contains(req.roleId())) {
                        throw ApiException.unprocessable("ROLE_ALREADY_CONFIRMED",
                                "该角色已确认此锁定点，须两名不同角色分别确认；锁定点 "
                                        + target.lockPointId() + " 角色 " + req.roleId()
                                        + " 已确认，要求角色数 " + REQUIRED_ROLE_COUNT
                                        + "，已确认角色数 " + confirmedRoles.size());
                    }
                    if (confirmedRoles.size() >= REQUIRED_ROLE_COUNT) {
                        throw ApiException.unprocessable("LOCK_POINT_ALREADY_CONFIRMED",
                                "该锁定点已集齐 " + REQUIRED_ROLE_COUNT
                                        + " 名不同角色确认，无需再确认：" + target.lockPointId());
                    }
                    Instant now = clock.instant();
                    repository.insertConfirmation(orderId, req.lockPointId(), req.operatorId(),
                            req.roleId(), req.requestId(), now);

                    // 判定该确认是否使隔离生效：所有锁定点均集齐两名不同角色
                    Map<String, Set<String>> rolesByLockPoint = collectRolesByLockPoint(orderId);
                    rolesByLockPoint.get(req.lockPointId()).add(req.roleId());
                    boolean allComplete = lockPoints.stream()
                            .allMatch(lp -> rolesByLockPoint.getOrDefault(lp.lockPointId(), Set.of())
                                    .size() >= REQUIRED_ROLE_COUNT);
                    if (allComplete && IsolationOrder.PENDING.equals(lockedOrder.status())) {
                        repository.activateOrder(orderId, now, req.operatorId(), req.requestId());
                        IsolationOrder activated = repository.findOrder(orderId).orElseThrow();
                        equipmentRepository.incrementVersion(activated.equipmentId());
                        return assemble(activated, equipment.version() + 1);
                    }
                    return assemble(lockedOrder, equipment.version());
                });
    }

    // ---------- 解除隔离 ----------

    @Transactional
    public IsolationOrderResponse release(String orderId, ReleaseIsolationRequest req) {
        IsolationOrder lockedOrder = lockOrder(orderId);
        List<LockReleaseInput> normalizedReleases = normalizeReleases(req.releases());
        // 规范化解除集合（按规范化 JSON 排序）后入指纹，集合顺序不影响指纹。
        List<ObjectNode> releaseBodies = normalizedReleases.stream()
                .map(r -> objectMapper.createObjectNode()
                        .put("lockPointId", r.lockPointId())
                        .put("operatorId", r.operatorId()))
                .sorted(java.util.Comparator.comparing(ObjectNode::toString))
                .toList();
        ObjectNode body = objectMapper.createObjectNode()
                .put("orderId", orderId)
                .put("reason", req.reason());
        body.putPOJO("releases", releaseBodies);
        String fingerprint = IsolationFingerprint.of(objectMapper, req.operatorId(),
                "RELEASE_ISOLATION", body);
        return idempotency.execute(req.requestId(), "RELEASE_ISOLATION", fingerprint,
                IsolationOrderResponse.class, () -> {
                    Equipment equipment = equipmentRepository.findEquipmentForUpdate(lockedOrder.equipmentId())
                            .orElseThrow(() -> equipmentNotFound(lockedOrder.equipmentId()));
                    if (!IsolationOrder.ACTIVE.equals(lockedOrder.status())) {
                        throw ApiException.unprocessable("ISOLATION_NOT_ACTIVE",
                                "仅隔离生效（ACTIVE）状态可解除，当前状态 " + lockedOrder.status());
                    }
                    List<IsolationLockPoint> lockPoints = repository.listLockPoints(orderId);
                    Map<String, IsolationLockPoint> required = lockPoints.stream()
                            .collect(Collectors.toMap(IsolationLockPoint::lockPointId, lp -> lp,
                                    (a, b) -> a, LinkedHashMap::new));
                    Set<String> submitted = normalizedReleases.stream()
                            .map(LockReleaseInput::lockPointId)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    // 必须逐一覆盖：无多余、无缺失、无重复
                    List<String> missing = lockPoints.stream()
                            .map(IsolationLockPoint::lockPointId)
                            .filter(id -> !submitted.contains(id))
                            .toList();
                    List<String> unexpected = submitted.stream()
                            .filter(id -> !required.containsKey(id))
                            .toList();
                    if (!missing.isEmpty() || !unexpected.isEmpty()) {
                        throw ApiException.unprocessable("PARTIAL_LOCK_RELEASE",
                                "解除必须覆盖全部锁定点：要求 " + required.size() + " 个，实际 "
                                        + submitted.size() + " 个；缺失 " + missing + "，多余 " + unexpected);
                    }
                    Instant now = clock.instant();
                    for (LockReleaseInput release : normalizedReleases) {
                        repository.insertLockRelease(orderId, release.lockPointId(), release.operatorId(),
                                req.requestId(), now);
                    }
                    repository.releaseOrder(orderId, now, req.operatorId(), req.reason(), req.requestId());
                    IsolationOrder released = repository.findOrder(orderId).orElseThrow();
                    equipmentRepository.incrementVersion(released.equipmentId());
                    return assemble(released, equipment.version() + 1);
                });
    }

    /** 单设备创建指纹（供外观层并发唯一键冲突补偿时重放判定复用）。 */
    public String createFingerprint(String equipmentId, CreateIsolationRequest req) {
        return IsolationFingerprint.of(objectMapper, req.createdBy(), "CREATE_ISOLATION",
                createBody(equipmentId, req.orderId(), req.expectedVersion(), req.lockPoints()));
    }

    /** 批量创建指纹（供外观层在并发唯一键冲突补偿时重放判定复用）。 */
    public String batchFingerprint(BatchCreateIsolationRequest req) {
        List<ObjectNode> itemBodies = req.orders().stream()
                .map(item -> createBody(item.equipmentId(), item.orderId(), item.expectedVersion(),
                        item.lockPoints()))
                .toList();
        return IsolationFingerprint.ofCollection(objectMapper, req.createdBy(),
                "BATCH_CREATE_ISOLATION", itemBodies);
    }

    // ---------- 查询：明细 / 历史 / 诊断 ----------

    @Transactional(readOnly = true)
    public IsolationOrderResponse getOrder(String orderId) {
        IsolationOrder order = repository.findOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("ISOLATION_ORDER_NOT_FOUND",
                        "隔离令不存在：" + orderId));
        long version = equipmentRepository.findEquipment(order.equipmentId())
                .map(Equipment::version).orElse(0L);
        return assemble(order, version);
    }

    @Transactional(readOnly = true)
    public List<IsolationOrderResponse> listOrders(String equipmentId) {
        equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        long version = equipmentRepository.findEquipment(equipmentId).map(Equipment::version).orElse(0L);
        return repository.listOrders(equipmentId).stream()
                .map(order -> assemble(order, version))
                .toList();
    }

    /**
     * 隔离历史（创建/生效/解除事件），按事件发生时刻升序、同刻按隔离令与事件类型稳定排序，
     * 最终赋予连续事件序号。
     */
    @Transactional(readOnly = true)
    public List<IsolationHistoryEvent> history(String equipmentId) {
        equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        record Event(String orderId, String type, int typeOrdinal, String operatorId,
                     String requestId, Instant occurredAt, String reason) {
        }
        List<Event> events = new ArrayList<>();
        for (IsolationOrder order : repository.listOrders(equipmentId)) {
            events.add(new Event(order.orderId(), "CREATED", 0, order.createdBy(),
                    order.requestId(), order.createdAt(), null));
            if (order.activatedAt() != null) {
                events.add(new Event(order.orderId(), "ACTIVATED", 1, order.activatedBy(),
                        order.activatedRequestId(), order.activatedAt(), null));
            }
            if (order.releasedAt() != null) {
                events.add(new Event(order.orderId(), "RELEASED", 2, order.releasedBy(),
                        order.releaseRequestId(), order.releasedAt(), order.releaseReason()));
            }
        }
        events.sort(java.util.Comparator.comparing(Event::occurredAt)
                .thenComparing(Event::orderId)
                .thenComparingInt(Event::typeOrdinal));
        List<IsolationHistoryEvent> result = new ArrayList<>();
        long seq = 1;
        for (Event event : events) {
            result.add(new IsolationHistoryEvent(seq++, event.orderId(), equipmentId, event.type(),
                    event.operatorId(), event.requestId(), event.occurredAt(), event.reason()));
        }
        return result;
    }

    /** 诊断：隔离生效期被拒读数证据，按拒绝时刻升序。 */
    @Transactional(readOnly = true)
    public List<IsolatedReadingRejectionView> rejections(String equipmentId) {
        equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listRejections(equipmentId).stream()
                .map(row -> new IsolatedReadingRejectionView(row.rejectionId(), row.equipmentId(),
                        row.orderId(), row.readingId(), row.operatorId(), row.requestId(),
                        row.sampledAt(), row.cumulativeMinutes(), row.lockPointCount(),
                        row.lockPointSnapshot(), row.rejectedAt()))
                .toList();
    }

    /**
     * 设备当前是否存在 ACTIVE 隔离令（供新增读数门禁判定）。调用方须已持有设备行锁。
     */
    @Transactional(readOnly = true)
    public Optional<IsolationOrder> findActiveOrder(String equipmentId) {
        return repository.findActiveOrder(equipmentId);
    }

    /** 拒绝时读取锁定点标识排序快照（业务证据）。 */
    @Transactional(readOnly = true)
    public List<String> lockPointSnapshot(String orderId) {
        return repository.listLockPoints(orderId).stream()
                .map(IsolationLockPoint::lockPointId)
                .sorted()
                .toList();
    }

    // ---------- 内部规则 ----------

    private void persistOrder(String equipmentId, String orderId, String createdBy, String requestId,
                              Instant now, List<LockPointInput> lockPoints) {
        repository.insertOrder(new IsolationOrder(orderId, equipmentId, IsolationOrder.PENDING,
                createdBy, requestId, now, null, null, null, null, null, null, null));
        int ordinal = 0;
        for (LockPointInput lp : lockPoints) {
            repository.insertLockPoint(orderId, lp.lockPointId(), ordinal++, lp.name());
        }
    }

    private ObjectNode createBody(String equipmentId, String orderId, long expectedVersion,
                                  List<LockPointInput> lockPoints) {
        ObjectNode body = objectMapper.createObjectNode()
                .put("equipmentId", equipmentId)
                .put("orderId", orderId)
                .put("expectedVersion", expectedVersion);
        // 规范化对象集合：按规范化 JSON 排序后入指纹，集合顺序不影响指纹。
        List<ObjectNode> points = lockPoints.stream()
                .map(lp -> objectMapper.createObjectNode()
                        .put("lockPointId", lp.lockPointId())
                        .put("name", lp.name()))
                .sorted(java.util.Comparator.comparing(ObjectNode::toString))
                .toList();
        body.putPOJO("lockPoints", points);
        return body;
    }

    private void assertDistinctLockPoints(List<LockPointInput> lockPoints) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicated = new ArrayList<>();
        for (LockPointInput lp : lockPoints) {
            if (!seen.add(lp.lockPointId()) && !duplicated.contains(lp.lockPointId())) {
                duplicated.add(lp.lockPointId());
            }
        }
        if (!duplicated.isEmpty()) {
            throw ApiException.unprocessable("DUPLICATE_LOCK_POINT",
                    "同一隔离令内锁定点标识重复：" + duplicated + "；要求集合元素唯一，实际提交 "
                            + lockPoints.size() + " 个，去重后 " + seen.size() + " 个");
        }
    }

    private void assertOrderIdAvailable(String orderId) {
        if (repository.findOrder(orderId).isPresent()) {
            throw ApiException.unprocessable("ISOLATION_ORDER_EXISTS",
                    "隔离令标识已被占用：" + orderId);
        }
    }

    private void assertBatchItemsDistinct(BatchCreateIsolationRequest req) {
        Set<String> equipmentIds = new LinkedHashSet<>();
        Set<String> orderIds = new LinkedHashSet<>();
        List<String> duplicatedEquipment = new ArrayList<>();
        List<String> duplicatedOrder = new ArrayList<>();
        for (BatchIsolationItem item : req.orders()) {
            if (!equipmentIds.add(item.equipmentId()) && !duplicatedEquipment.contains(item.equipmentId())) {
                duplicatedEquipment.add(item.equipmentId());
            }
            if (!orderIds.add(item.orderId()) && !duplicatedOrder.contains(item.orderId())) {
                duplicatedOrder.add(item.orderId());
            }
        }
        if (!duplicatedEquipment.isEmpty() || !duplicatedOrder.isEmpty()) {
            throw ApiException.unprocessable("BATCH_ITEM_DUPLICATE",
                    "批量请求内设备或隔离令重复：重复设备 " + duplicatedEquipment + "，重复隔离令 "
                            + duplicatedOrder);
        }
    }

    private List<LockReleaseInput> normalizeReleases(List<LockReleaseInput> releases) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicated = new ArrayList<>();
        for (LockReleaseInput release : releases) {
            if (!seen.add(release.lockPointId()) && !duplicated.contains(release.lockPointId())) {
                duplicated.add(release.lockPointId());
            }
        }
        if (!duplicated.isEmpty()) {
            throw ApiException.unprocessable("PARTIAL_LOCK_RELEASE",
                    "解除请求内锁定点重复：" + duplicated + "；必须逐一解除且不得重复");
        }
        return releases;
    }

    private Map<String, Set<String>> collectRolesByLockPoint(String orderId) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (IsolationConfirmation c : repository.listConfirmations(orderId)) {
            map.computeIfAbsent(c.lockPointId(), k -> new LinkedHashSet<>()).add(c.roleId());
        }
        return map;
    }

    private IsolationOrder lockOrder(String orderId) {
        return repository.findOrderForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("ISOLATION_ORDER_NOT_FOUND",
                        "隔离令不存在：" + orderId));
    }

    private Equipment lockEquipment(String equipmentId) {
        return equipmentRepository.findEquipmentForUpdate(equipmentId)
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

    /** 组装明细视图：统计实际值、要求值、差额，并固化稳定排序。 */
    private IsolationOrderResponse assemble(IsolationOrder order, long equipmentVersion) {
        List<IsolationLockPoint> lockPoints = repository.listLockPoints(order.orderId());
        List<IsolationConfirmation> confirmations = repository.listConfirmations(order.orderId());
        Set<String> releasedIds = new LinkedHashSet<>(repository.listReleasedLockPointIds(order.orderId()));

        int confirmedCount = 0;
        int releasedCount = releasedIds.size();
        List<LockPointDetail> details = new ArrayList<>();
        for (IsolationLockPoint lp : lockPoints) {
            List<ConfirmationView> lpConfirmations = confirmations.stream()
                    .filter(c -> c.lockPointId().equals(lp.lockPointId()))
                    .map(c -> new ConfirmationView(c.operatorId(), c.roleId(), c.requestId(),
                            c.confirmedAt()))
                    .toList();
            long roleCount = lpConfirmations.stream().map(ConfirmationView::roleId).distinct().count();
            if (roleCount >= REQUIRED_ROLE_COUNT) {
                confirmedCount++;
            }
            details.add(new LockPointDetail(lp.lockPointId(), lp.ordinal(), lp.name(),
                    REQUIRED_ROLE_COUNT, (int) roleCount, REQUIRED_ROLE_COUNT - (int) roleCount,
                    releasedIds.contains(lp.lockPointId()), lpConfirmations));
        }
        int total = lockPoints.size();
        return new IsolationOrderResponse(order.orderId(), order.equipmentId(), order.status(),
                order.createdBy(), order.createdAt(), order.activatedAt(), order.releasedAt(),
                order.releasedBy(), order.releaseReason(), total, confirmedCount,
                total - confirmedCount, releasedCount, total - releasedCount,
                order.activatedAt(), order.releasedAt(), details);
    }
}
