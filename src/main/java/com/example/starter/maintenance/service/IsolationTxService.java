package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.BatchCreateIsolationRequest;
import com.example.starter.maintenance.api.dto.BatchCreateIsolationResponse;
import com.example.starter.maintenance.api.dto.BatchIsolationItem;
import com.example.starter.maintenance.api.dto.ConfirmationView;
import com.example.starter.maintenance.api.dto.ConfirmLockPointsRequest;
import com.example.starter.maintenance.api.dto.CreateIsolationRequest;
import com.example.starter.maintenance.api.dto.IsolationOrderResponse;
import com.example.starter.maintenance.api.dto.LockEventView;
import com.example.starter.maintenance.api.dto.LockPointReleaseInput;
import com.example.starter.maintenance.api.dto.LockPointView;
import com.example.starter.maintenance.api.dto.ReleaseIsolationRequest;
import com.example.starter.maintenance.api.dto.RejectedReadingView;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.IsolationOrder;
import com.example.starter.maintenance.domain.LockConfirmation;
import com.example.starter.maintenance.domain.LockEvent;
import com.example.starter.maintenance.domain.RejectedReading;
import com.example.starter.maintenance.store.EquipmentRepository;
import com.example.starter.maintenance.store.IsolationRepository;
import com.example.starter.maintenance.store.IsolationRepository.LockPointRow;

/**
 * 能源隔离令事务服务。写操作均先锁隔离令行再锁设备行（读数写操作只持设备行锁），
 * 按事务提交顺序裁决隔离生效与读数写入；多对象写入先完成全部最终状态校验，再在单事务内提交。
 * 幂等记录与业务变更同事务提交，业务失败整体回滚不占键。
 */
@Service
public class IsolationTxService {

    private final IsolationRepository isolationRepository;
    private final EquipmentRepository equipmentRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public IsolationTxService(IsolationRepository isolationRepository,
                              EquipmentRepository equipmentRepository,
                              IdempotencyService idempotency,
                              Clock clock) {
        this.isolationRepository = isolationRepository;
        this.equipmentRepository = equipmentRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /** 批量项的规范化最终形态：设备/隔离令升序、锁定点升序去重。 */
    public record NormalizedItem(String equipmentId, String orderId, List<String> lockPointIds) {
    }

    // ---------- 幂等指纹（外观层并发补偿与事务层共用，保证一致） ----------

    static String createFingerprint(CreateIsolationRequest req) {
        return IsolationFingerprints.createIsolation(req.operator(),
                normalizeDistinctPoints(req.lockPointIds(), req.orderId()));
    }

    static String batchFingerprint(BatchCreateIsolationRequest req) {
        List<NormalizedItem> normalizedItems = normalizeBatchItems(req.orders());
        return IsolationFingerprints.batchCreateIsolation(req.operator(), normalizedItems);
    }

    static String confirmFingerprint(ConfirmLockPointsRequest req) {
        if (req.pointIds().size() != new HashSet<>(req.pointIds()).size()) {
            throw ApiException.unprocessable("LOCK_POINT_DUPLICATE", "本次请求的锁定点集合存在重复");
        }
        return IsolationFingerprints.confirmLockPoints(req.operator(), req.role(),
                normalizePointList(req.pointIds()));
    }

    static String releaseFingerprint(ReleaseIsolationRequest req) {
        return IsolationFingerprints.releaseIsolation(req.operator(), req.reason(),
                normalizeReleases(req.releases()));
    }

    private static List<NormalizedItem> normalizeBatchItems(List<BatchIsolationItem> items) {
        return items.stream()
                .sorted(Comparator.comparing(BatchIsolationItem::equipmentId)
                        .thenComparing(BatchIsolationItem::orderId))
                .map(item -> new NormalizedItem(item.equipmentId(), item.orderId(),
                        normalizeDistinctPoints(item.lockPointIds(),
                                "隔离令 " + item.orderId() + "（设备 " + item.equipmentId() + "）")))
                .toList();
    }

    private static Map<String, String> normalizeReleases(List<LockPointReleaseInput> releases) {
        if (releases.size() != releases.stream().map(LockPointReleaseInput::pointId).distinct().count()) {
            throw ApiException.unprocessable("LOCK_POINT_DUPLICATE", "本次请求的锁定点集合存在重复");
        }
        Map<String, String> normalized = new TreeMap<>();
        for (LockPointReleaseInput input : releases) {
            normalized.put(input.pointId(), input.reason());
        }
        return normalized;
    }

    /** 规范化锁定点集合：升序去重；出现重复直接 422（不静默去重）。 */
    private static List<String> normalizeDistinctPoints(List<String> pointIds, String context) {
        List<String> sorted = pointIds.stream().sorted().toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).equals(sorted.get(i - 1))) {
                throw ApiException.unprocessable("LOCK_POINT_DUPLICATE",
                        context + " 锁定点重复：" + sorted.get(i));
            }
        }
        return sorted;
    }

    private static List<String> normalizePointList(List<String> pointIds) {
        return pointIds.stream().sorted().toList();
    }

    // ---------- 创建隔离令 ----------

    @Transactional
    public IsolationOrderResponse createIsolation(String equipmentId, CreateIsolationRequest req) {
        Equipment equipment = equipmentRepository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        List<String> normalizedPoints = normalizeDistinctPoints(
                req.lockPointIds(), "隔离令 " + req.orderId() + "（设备 " + equipmentId + "）");
        return idempotency.execute(req.requestId(), "CREATE_ISOLATION",
                IsolationFingerprints.createIsolation(req.operator(), normalizedPoints),
                IsolationOrderResponse.class, () -> {
                    checkCanCreateOrder(equipment, req.orderId());
                    return insertOrder(equipmentId, req.orderId(), req.operator(), normalizedPoints,
                            clock.instant());
                });
    }

    // ---------- 批量创建隔离令 ----------

    @Transactional
    public BatchCreateIsolationResponse batchCreateIsolations(BatchCreateIsolationRequest req) {
        List<BatchIsolationItem> items = req.orders();
        // 第一批校验（无需持锁）：批内对象重复与各令锁定点重复，失败响应标明具体设备/隔离令。
        checkBatchStructure(items);

        // 按设备标识排序加锁，避免多设备并发批量互锁；缺设备在持锁阶段判 404。
        List<BatchIsolationItem> lockedOrder = items.stream()
                .sorted(Comparator.comparing(BatchIsolationItem::equipmentId)
                        .thenComparing(BatchIsolationItem::orderId))
                .toList();
        Map<String, Equipment> lockedEquipment = new TreeMap<>();
        for (BatchIsolationItem item : lockedOrder) {
            Equipment equipment = equipmentRepository.findEquipmentForUpdate(item.equipmentId())
                    .orElseThrow(() -> equipmentNotFound(item.equipmentId()));
            lockedEquipment.put(item.equipmentId(), equipment);
        }

        // 规范化对象集合：设备升序、每令锁定点升序，作为幂等指纹。
        List<NormalizedItem> normalizedItems = normalizeBatchItems(items);

        return idempotency.execute(req.requestId(), "BATCH_CREATE_ISOLATION",
                IsolationFingerprints.batchCreateIsolation(req.operator(), normalizedItems),
                BatchCreateIsolationResponse.class, () -> {
                    // 第二批校验（持锁最终状态）：任一失败整批不生效，此时尚未写入任何隔离令。
                    for (BatchIsolationItem item : lockedOrder) {
                        checkCanCreateOrder(lockedEquipment.get(item.equipmentId()), item.orderId());
                    }
                    Instant now = clock.instant();
                    List<IsolationOrderResponse> responses = new ArrayList<>();
                    for (NormalizedItem item : normalizedItems) {
                        responses.add(insertOrder(item.equipmentId(), item.orderId(),
                                req.operator(), item.lockPointIds(), now));
                    }
                    return new BatchCreateIsolationResponse(responses.size(), items.size(), responses);
                });
    }

    // ---------- 角色确认锁定点 ----------

    @Transactional
    public IsolationOrderResponse confirmLockPoints(String equipmentId, String orderId,
                                                    ConfirmLockPointsRequest req) {
        IsolationOrder order = lockOrderAndEquipment(equipmentId, orderId);
        if (req.pointIds().size() != new HashSet<>(req.pointIds()).size()) {
            throw duplicatePoints(orderId);
        }
        List<String> normalizedPoints = normalizePointList(req.pointIds());
        return idempotency.execute(req.requestId(), "CONFIRM_LOCK_POINTS",
                confirmFingerprint(req),
                IsolationOrderResponse.class, () -> {
                    if (!IsolationOrder.PENDING.equals(order.state())) {
                        throw ApiException.unprocessable("ORDER_NOT_PENDING",
                                "隔离令当前状态为 " + order.state() + "，仅 PENDING 状态可确认锁定点");
                    }
                    List<LockPointRow> points = isolationRepository.listLockPointRows(orderId);
                    Map<String, LockPointRow> byPoint = points.stream()
                            .collect(Collectors.toMap(LockPointRow::pointId, p -> p));
                    List<LockPointRow> targets = new ArrayList<>();
                    for (String pointId : normalizedPoints) {
                        LockPointRow point = byPoint.get(pointId);
                        if (point == null) {
                            throw ApiException.unprocessable("LOCK_POINT_NOT_FOUND",
                                    "锁定点不属于该隔离令：" + pointId + "（实际提交 " + normalizedPoints.size()
                                            + " 个，要求均属于该隔离令现存的 " + points.size() + " 个锁定点）");
                        }
                        targets.add(point);
                    }
                    // 先校验完整最终状态：同角色对任一点重复确认（同一角色双确认）整批拒绝，不写入任何事件。
                    List<LockEvent> events = isolationRepository.listLockEvents(orderId);
                    for (LockPointRow point : targets) {
                        boolean already = events.stream().anyMatch(e -> LockEvent.CONFIRM.equals(e.eventType())
                                && req.role().equals(e.role()) && point.pointId().equals(e.pointId()));
                        if (already) {
                            throw ApiException.unprocessable("LOCK_POINT_ALREADY_CONFIRMED",
                                    "角色 " + req.role() + " 已确认锁定点：" + point.pointId()
                                            + "（同一角色不可二次确认）");
                        }
                    }
                    Instant now = clock.instant();
                    for (LockPointRow point : targets) {
                        isolationRepository.insertLockEvent(orderId, point.seqNo(), point.pointId(),
                                LockEvent.CONFIRM, req.role(), req.operator(), req.requestId(), null, now);
                    }
                    // 全部锁定点均经两名不同角色确认后，隔离令整体生效。
                    if (isFullyConfirmed(points, isolationRepository.listLockEvents(orderId))) {
                        isolationRepository.markEffective(orderId, now);
                    }
                    return assembleResponse(isolationRepository.findOrder(orderId).orElseThrow());
                });
    }

    // ---------- 解除隔离令 ----------

    @Transactional
    public IsolationOrderResponse releaseIsolation(String equipmentId, String orderId,
                                                   ReleaseIsolationRequest req) {
        IsolationOrder order = lockOrderAndEquipment(equipmentId, orderId);
        List<LockPointReleaseInput> releases = req.releases();
        Map<String, String> normalizedReleases = normalizeReleases(releases);
        List<String> requestedPointIds = List.copyOf(normalizedReleases.keySet());
        return idempotency.execute(req.requestId(), "RELEASE_ISOLATION",
                IsolationFingerprints.releaseIsolation(req.operator(), req.reason(), normalizedReleases),
                IsolationOrderResponse.class, () -> {
                    if (IsolationOrder.PENDING.equals(order.state())) {
                        throw ApiException.unprocessable("ORDER_NOT_EFFECTIVE",
                                "隔离令尚未生效（PENDING），须双角色确认全部锁定点后方可解除");
                    }
                    if (IsolationOrder.RELEASED.equals(order.state())) {
                        throw ApiException.unprocessable("ORDER_ALREADY_RELEASED",
                                "隔离令已解除，不可重复解除");
                    }
                    List<LockPointRow> points = isolationRepository.listLockPointRows(orderId);
                    Map<String, LockPointRow> byPoint = points.stream()
                            .collect(Collectors.toMap(LockPointRow::pointId, p -> p));
                    // 先校验完整最终状态：集合须恰好覆盖全部锁定点；多余/缺失/部分解除均 422，不产生半成品。
                    for (String pointId : requestedPointIds) {
                        if (!byPoint.containsKey(pointId)) {
                            throw ApiException.unprocessable("LOCK_POINT_NOT_FOUND",
                                    "锁定点不属于该隔离令：" + pointId);
                        }
                    }
                    if (requestedPointIds.size() != points.size()) {
                        throw ApiException.unprocessable("INCOMPLETE_RELEASE",
                                "解除必须覆盖全部锁定点：实际提交 " + requestedPointIds.size()
                                        + " 个，要求 " + points.size() + " 个，差额 "
                                        + (points.size() - requestedPointIds.size())
                                        + " 个；部分解除不予受理");
                    }
                    Instant now = clock.instant();
                    for (LockPointReleaseInput input : releases) {
                        LockPointRow point = byPoint.get(input.pointId());
                        isolationRepository.insertLockEvent(orderId, point.seqNo(), point.pointId(),
                                LockEvent.RELEASE, null, req.operator(), req.requestId(),
                                input.reason(), now);
                        isolationRepository.releaseLockPoint(orderId, point.pointId(), now,
                                req.operator(), input.reason());
                    }
                    isolationRepository.markReleased(orderId, now, req.operator(), req.reason());
                    return assembleResponse(isolationRepository.findOrder(orderId).orElseThrow());
                });
    }

    // ---------- 被拒读数证据（独立事务提交，不受外层 409 回滚影响） ----------

    /**
     * 固化隔离生效期间被拒的新增读数证据。使用独立事务：外层读数事务随后回滚（不占幂等键），
     * 证据仍可在诊断查询中查到。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejectedReading(String equipmentId, String orderId, String readingId,
                                      Instant sampledAt, long cumulativeMinutes,
                                      String requestedBy, String requestId) {
        isolationRepository.insertRejectedReading(new RejectedReading(
                0L, equipmentId, orderId, readingId, sampledAt, cumulativeMinutes,
                requestedBy, requestId, clock.instant()));
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public IsolationOrderResponse getOrderForEquipment(String equipmentId, String orderId) {
        IsolationOrder order = loadOrder(orderId);
        if (!equipmentId.equals(order.equipmentId())) {
            throw orderNotFound(orderId);
        }
        return assembleResponse(order);
    }

    @Transactional(readOnly = true)
    public List<IsolationOrderResponse> listOrdersForEquipment(String equipmentId) {
        equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return isolationRepository.listOrdersByEquipment(equipmentId).stream()
                .map(this::assembleResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LockEventView> listLockEvents(String orderId) {
        loadOrder(orderId);
        return isolationRepository.listLockEvents(orderId).stream()
                .map(e -> new LockEventView(e.id(), e.orderId(), e.seqNo(), e.pointId(),
                        e.eventType(), e.role(), e.operator(), e.requestId(), e.reason(), e.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RejectedReadingView> listRejectedReadings(String equipmentId) {
        equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return isolationRepository.listRejectedReadings(equipmentId).stream()
                .map(r -> new RejectedReadingView(r.id(), r.equipmentId(), r.orderId(), r.readingId(),
                        r.sampledAt(), r.cumulativeMinutes(), r.requestedBy(), r.requestId(), r.rejectedAt()))
                .toList();
    }

    // ---------- 内部规则与装配 ----------

    private IsolationOrder lockOrderAndEquipment(String equipmentId, String orderId) {
        IsolationOrder order = isolationRepository.findOrderForUpdate(orderId)
                .orElseThrow(() -> orderNotFound(orderId));
        // 再锁设备行：与读数/停用等设备写操作按提交顺序串行裁决。
        equipmentRepository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        if (!equipmentId.equals(order.equipmentId())) {
            throw orderNotFound(orderId);
        }
        return order;
    }

    private IsolationOrder loadOrder(String orderId) {
        return isolationRepository.findOrder(orderId)
                .orElseThrow(() -> orderNotFound(orderId));
    }

    private void checkCanCreateOrder(Equipment equipment, String orderId) {
        if (Equipment.DECOMMISSIONED.equals(equipment.status())) {
            throw ApiException.unprocessable("EQUIPMENT_DECOMMISSIONED",
                    "设备已停用，不可创建能源隔离令：" + equipment.equipmentId());
        }
        Optional<IsolationOrder> open = isolationRepository.findOpenOrderForEquipment(equipment.equipmentId());
        if (open.isPresent()) {
            throw ApiException.unprocessable("OPEN_ISOLATION_EXISTS",
                    "设备已存在未结束的隔离令 " + open.get().orderId() + "（状态 " + open.get().state()
                            + "），须解除后方可新建");
        }
        if (isolationRepository.findOrder(orderId).isPresent()) {
            throw ApiException.conflict("ISOLATION_ORDER_EXISTS", "隔离令标识已被占用：" + orderId);
        }
    }

    private void checkBatchStructure(List<BatchIsolationItem> items) {
        List<String> equipmentIds = items.stream().map(BatchIsolationItem::equipmentId).sorted().toList();
        for (int i = 1; i < equipmentIds.size(); i++) {
            if (equipmentIds.get(i).equals(equipmentIds.get(i - 1))) {
                throw ApiException.unprocessable("BATCH_DUPLICATE_TARGET",
                        "批量隔离中设备重复：" + equipmentIds.get(i));
            }
        }
        List<String> orderIds = items.stream().map(BatchIsolationItem::orderId).sorted().toList();
        for (int i = 1; i < orderIds.size(); i++) {
            if (orderIds.get(i).equals(orderIds.get(i - 1))) {
                throw ApiException.unprocessable("BATCH_DUPLICATE_TARGET",
                        "批量隔离中隔离令标识重复：" + orderIds.get(i));
            }
        }
        // 逐令锁定点结构校验（重复直接 422）。
        for (BatchIsolationItem item : items) {
            normalizeDistinctPoints(item.lockPointIds(),
                    "隔离令 " + item.orderId() + "（设备 " + item.equipmentId() + "）");
        }
    }

    private IsolationOrderResponse insertOrder(String equipmentId, String orderId, String operator,
                                               List<String> points, Instant now) {
        isolationRepository.insertOrder(new IsolationOrder(orderId, equipmentId,
                IsolationOrder.PENDING, operator, now, null, null, null, null));
        for (int i = 0; i < points.size(); i++) {
            isolationRepository.insertLockPoint(orderId, i + 1, points.get(i));
        }
        return assembleResponse(isolationRepository.findOrder(orderId).orElseThrow());
    }

    private ApiException duplicatePoints(String orderId) {
        return ApiException.unprocessable("LOCK_POINT_DUPLICATE",
                "隔离令 " + orderId + " 本次请求的锁定点集合存在重复");
    }

    /** 全部锁定点均有 ELECTRICIAN 与 MECHANIC 两个不同角色确认时方可生效。 */
    private boolean isFullyConfirmed(List<LockPointRow> points, List<LockEvent> events) {
        for (LockPointRow point : points) {
            long roleCount = events.stream()
                    .filter(e -> LockEvent.CONFIRM.equals(e.eventType())
                            && point.pointId().equals(e.pointId()))
                    .map(LockEvent::role)
                    .distinct()
                    .count();
            if (roleCount < 2) {
                return false;
            }
        }
        return true;
    }

    private IsolationOrderResponse assembleResponse(IsolationOrder order) {
        List<LockPointRow> rows = isolationRepository.listLockPointRows(order.orderId());
        List<LockEvent> events = isolationRepository.listLockEvents(order.orderId());
        List<LockPointView> pointViews = new ArrayList<>();
        int confirmationCount = 0;
        int confirmedPointCount = 0;
        int releasedPointCount = 0;
        for (LockPointRow row : rows) {
            List<LockConfirmation> confirmations = events.stream()
                    .filter(e -> LockEvent.CONFIRM.equals(e.eventType())
                            && row.pointId().equals(e.pointId()))
                    .map(e -> new LockConfirmation(e.role(), e.operator(), e.createdAt()))
                    .sorted(Comparator.comparing(LockConfirmation::role))
                    .toList();
            confirmationCount += confirmations.size();
            if (confirmations.stream().map(LockConfirmation::role).distinct().count() >= 2) {
                confirmedPointCount++;
            }
            if (row.releasedAt() != null) {
                releasedPointCount++;
            }
            pointViews.add(new LockPointView(
                    row.seqNo(),
                    row.pointId(),
                    confirmations.stream()
                            .map(c -> new ConfirmationView(c.role(), c.operator(), c.confirmedAt()))
                            .toList(),
                    row.releasedAt(),
                    row.releasedBy(),
                    row.releaseReason()));
        }
        int requiredPointCount = rows.size();
        int requiredConfirmationCount = requiredPointCount * 2;
        return new IsolationOrderResponse(
                order.orderId(),
                order.equipmentId(),
                order.state(),
                order.createdBy(),
                order.createdAt(),
                order.effectiveAt(),
                order.releasedAt(),
                order.releasedBy(),
                order.releaseReason(),
                pointViews,
                requiredPointCount,
                confirmedPointCount,
                requiredConfirmationCount,
                confirmationCount,
                Math.max(0, requiredConfirmationCount - confirmationCount),
                releasedPointCount,
                requiredPointCount - releasedPointCount);
    }

    private ApiException equipmentNotFound(String equipmentId) {
        return ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在：" + equipmentId);
    }

    private ApiException orderNotFound(String orderId) {
        return ApiException.notFound("ISOLATION_ORDER_NOT_FOUND", "隔离令不存在：" + orderId);
    }
}
