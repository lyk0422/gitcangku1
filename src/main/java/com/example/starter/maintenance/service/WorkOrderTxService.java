package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.CreateWorkOrderRequest;
import com.example.starter.maintenance.api.dto.RegisterWorkOrderReadingsRequest;
import com.example.starter.maintenance.api.dto.WorkOrderOperationRequest;
import com.example.starter.maintenance.api.dto.WorkOrderReadingItem;
import com.example.starter.maintenance.api.dto.WorkOrderResponse;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.domain.WorkOrder;
import com.example.starter.maintenance.domain.WorkOrderStatus;
import com.example.starter.maintenance.store.EquipmentRepository;
import com.example.starter.maintenance.store.WorkOrderRepository;

/**
 * 保养工单事务业务服务。写操作流程：设备行锁 → 工单行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 建单、开始、登记读数、关闭、取消、终止均按提交顺序（行锁串行化）裁决；
 * workOrderKey 幂等指纹含工单版本、设备、基线、窗口与读数摘要；失败整体回滚不占键、不留半成品状态。
 */
@Service
public class WorkOrderTxService {

    private final EquipmentRepository equipmentRepository;
    private final WorkOrderRepository workOrderRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public WorkOrderTxService(EquipmentRepository equipmentRepository,
                              WorkOrderRepository workOrderRepository,
                              IdempotencyService idempotency, Clock clock) {
        this.equipmentRepository = equipmentRepository;
        this.workOrderRepository = workOrderRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------- 建单 ----------

    @Transactional
    public WorkOrderResponse create(String equipmentId, CreateWorkOrderRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        // 同键工单已存在时以其冻结基线构造指纹，保证同键同参可重放、异参 409
        Optional<WorkOrder> existing = workOrderRepository.findByKey(req.workOrderKey());
        String fingerprint = createFingerprint(equipmentId, req, existing.orElse(null));
        return idempotency.execute(idempotencyKey(req.workOrderKey(), "CREATE", 1L), "WO_CREATE", fingerprint,
                WorkOrderResponse.class, () -> {
                    checkEquipmentVersion(equipment, req.expectedVersion());
                    if (!req.windowEnd().isAfter(req.windowStart())) {
                        throw ApiException.unprocessable("INVALID_WINDOW",
                                "登记窗口结束时刻必须晚于开始时刻");
                    }
                    if (existing.isPresent()) {
                        throw ApiException.conflict("WORK_ORDER_EXISTS",
                                "工单已存在：" + req.workOrderKey());
                    }
                    if (workOrderRepository.findActiveByEquipment(equipmentId).isPresent()) {
                        throw ApiException.conflict("WORK_ORDER_ACTIVE_EXISTS",
                                "设备存在未完结工单，同一时刻仅允许一个进行中的工单");
                    }
                    // 基线须为当前已认证读数（设备最新生效读数）
                    Reading baseline = equipmentRepository.findLatestReading(equipmentId)
                            .orElseThrow(() -> ApiException.unprocessable("NO_CERTIFIED_READING",
                                    "设备暂无已认证读数，无法冻结基线建单"));
                    Instant now = clock.instant();
                    WorkOrder order = new WorkOrder(req.workOrderKey(), equipmentId,
                            WorkOrderStatus.CREATED, 1L,
                            baseline.readingId(), baseline.sampledAt(), baseline.cumulativeMinutes(),
                            req.windowStart(), req.windowEnd(),
                            null, null, null, null, null, null, null, now, now);
                    workOrderRepository.insertWorkOrder(order);
                    return toResponse(order, equipment.version());
                });
    }

    // ---------- 开始 ----------

    @Transactional
    public WorkOrderResponse start(String equipmentId, String workOrderKey, WorkOrderOperationRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        WorkOrder order = lockWorkOrder(equipmentId, workOrderKey);
        String fingerprint = operationFingerprint(order, req.expectedWorkOrderVersion());
        return idempotency.execute(idempotencyKey(workOrderKey, "START", req.expectedWorkOrderVersion()),
                "WO_START", fingerprint,
                WorkOrderResponse.class, () -> {
                    checkWorkOrderVersion(order, req.expectedWorkOrderVersion());
                    requireNotTerminal(order);
                    if (order.status() != WorkOrderStatus.CREATED) {
                        throw ApiException.conflict("WORK_ORDER_ALREADY_STARTED",
                                "工单已开始，不能重复开始：" + workOrderKey);
                    }
                    Instant now = clock.instant();
                    WorkOrder updated = copy(order, WorkOrderStatus.STARTED, order.workOrderVersion() + 1,
                            now, order.closedAt(), order.cancelledAt(), order.terminatedAt(),
                            order.snapshotLastReadingId(), order.snapshotLastSampledAt(),
                            order.snapshotLastCumulativeMinutes(), now);
                    workOrderRepository.updateWorkOrder(updated);
                    return toResponse(updated, equipment.version());
                });
    }

    // ---------- 批量登记读数 ----------

    @Transactional
    public WorkOrderResponse registerReadings(String equipmentId, String workOrderKey,
                                              RegisterWorkOrderReadingsRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        WorkOrder order = lockWorkOrder(equipmentId, workOrderKey);
        String fingerprint = operationFingerprint(order, req.expectedWorkOrderVersion())
                + "|" + readingsDigest(req.readings());
        return idempotency.execute(idempotencyKey(workOrderKey, "READINGS", req.expectedWorkOrderVersion()),
                "WO_READINGS", fingerprint,
                WorkOrderResponse.class, () -> {
                    requireNotTerminal(order);
                    if (order.status() != WorkOrderStatus.STARTED) {
                        throw ApiException.conflict("WORK_ORDER_NOT_STARTED",
                                "工单未开始，不允许登记读数：" + workOrderKey);
                    }
                    // 批量预校验：版本失配 422
                    if (equipment.version() != req.expectedVersion()) {
                        throw ApiException.unprocessable("BATCH_VERSION_MISMATCH",
                                "设备版本失配：期望 " + req.expectedVersion()
                                        + "，当前 " + equipment.version());
                    }
                    if (order.workOrderVersion() != req.expectedWorkOrderVersion()) {
                        throw ApiException.unprocessable("BATCH_VERSION_MISMATCH",
                                "工单版本失配：期望 " + req.expectedWorkOrderVersion()
                                        + "，当前 " + order.workOrderVersion());
                    }
                    prevalidateBatch(equipmentId, order, req.readings());
                    // 全部校验通过才落库；任一失败抛异常，读数与工单状态整体回滚
                    Instant now = clock.instant();
                    List<WorkOrderReadingItem> sorted = req.readings().stream()
                            .sorted(Comparator.comparing(WorkOrderReadingItem::sampledAt)
                                    .thenComparing(WorkOrderReadingItem::readingId))
                            .toList();
                    for (WorkOrderReadingItem item : sorted) {
                        equipmentRepository.insertReading(
                                new Reading(equipmentId, item.readingId(), item.sampledAt(),
                                        item.cumulativeMinutes(), 1),
                                now);
                        equipmentRepository.insertRevision(equipmentId, item.readingId(), 1,
                                item.cumulativeMinutes(),
                                idempotencyKey(workOrderKey, "READINGS", req.expectedWorkOrderVersion()), now);
                    }
                    equipmentRepository.incrementVersion(equipmentId);
                    WorkOrder updated = copy(order, order.status(), order.workOrderVersion() + 1,
                            order.startedAt(), order.closedAt(), order.cancelledAt(), order.terminatedAt(),
                            order.snapshotLastReadingId(), order.snapshotLastSampledAt(),
                            order.snapshotLastCumulativeMinutes(), now);
                    workOrderRepository.updateWorkOrder(updated);
                    return toResponse(updated, equipment.version() + 1);
                });
    }

    // ---------- 关闭 ----------

    @Transactional
    public WorkOrderResponse close(String equipmentId, String workOrderKey, WorkOrderOperationRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        WorkOrder order = lockWorkOrder(equipmentId, workOrderKey);
        String fingerprint = operationFingerprint(order, req.expectedWorkOrderVersion());
        return idempotency.execute(idempotencyKey(workOrderKey, "CLOSE", req.expectedWorkOrderVersion()),
                "WO_CLOSE", fingerprint,
                WorkOrderResponse.class, () -> {
                    checkWorkOrderVersion(order, req.expectedWorkOrderVersion());
                    requireNotTerminal(order);
                    if (order.status() != WorkOrderStatus.STARTED) {
                        throw ApiException.conflict("WORK_ORDER_NOT_STARTED",
                                "工单未开始，不能关闭：" + workOrderKey);
                    }
                    Instant now = clock.instant();
                    // 不可变保养状态快照：基线 + 最后有效读数（无窗口内有效读数时取基线）+ 关闭时刻
                    Reading lastValid = equipmentRepository.findLastValidReadingInWindow(equipmentId,
                                    order.windowStart(), order.windowEnd(), order.baselineCumulativeMinutes())
                            .orElse(new Reading(equipmentId, order.baselineReadingId(),
                                    order.baselineSampledAt(), order.baselineCumulativeMinutes(), 0));
                    WorkOrder updated = copy(order, WorkOrderStatus.CLOSED, order.workOrderVersion() + 1,
                            order.startedAt(), now, order.cancelledAt(), order.terminatedAt(),
                            lastValid.readingId(), lastValid.sampledAt(), lastValid.cumulativeMinutes(), now);
                    workOrderRepository.updateWorkOrder(updated);
                    return toResponse(updated, equipment.version());
                });
    }

    // ---------- 取消 ----------

    @Transactional
    public WorkOrderResponse cancel(String equipmentId, String workOrderKey, WorkOrderOperationRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        WorkOrder order = lockWorkOrder(equipmentId, workOrderKey);
        String fingerprint = operationFingerprint(order, req.expectedWorkOrderVersion());
        return idempotency.execute(idempotencyKey(workOrderKey, "CANCEL", req.expectedWorkOrderVersion()),
                "WO_CANCEL", fingerprint,
                WorkOrderResponse.class, () -> {
                    checkWorkOrderVersion(order, req.expectedWorkOrderVersion());
                    requireNotTerminal(order);
                    if (order.status() != WorkOrderStatus.CREATED) {
                        throw ApiException.conflict("WORK_ORDER_ALREADY_STARTED",
                                "已开始工单不可取消，必须关闭或终止：" + workOrderKey);
                    }
                    Instant now = clock.instant();
                    WorkOrder updated = copy(order, WorkOrderStatus.CANCELLED, order.workOrderVersion() + 1,
                            order.startedAt(), order.closedAt(), now, order.terminatedAt(),
                            order.snapshotLastReadingId(), order.snapshotLastSampledAt(),
                            order.snapshotLastCumulativeMinutes(), now);
                    workOrderRepository.updateWorkOrder(updated);
                    return toResponse(updated, equipment.version());
                });
    }

    // ---------- 终止 ----------

    @Transactional
    public WorkOrderResponse terminate(String equipmentId, String workOrderKey, WorkOrderOperationRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        WorkOrder order = lockWorkOrder(equipmentId, workOrderKey);
        String fingerprint = operationFingerprint(order, req.expectedWorkOrderVersion());
        return idempotency.execute(idempotencyKey(workOrderKey, "TERMINATE", req.expectedWorkOrderVersion()),
                "WO_TERMINATE", fingerprint,
                WorkOrderResponse.class, () -> {
                    checkWorkOrderVersion(order, req.expectedWorkOrderVersion());
                    requireNotTerminal(order);
                    Instant now = clock.instant();
                    WorkOrder updated = copy(order, WorkOrderStatus.TERMINATED, order.workOrderVersion() + 1,
                            order.startedAt(), order.closedAt(), order.cancelledAt(), now,
                            order.snapshotLastReadingId(), order.snapshotLastSampledAt(),
                            order.snapshotLastCumulativeMinutes(), now);
                    workOrderRepository.updateWorkOrder(updated);
                    return toResponse(updated, equipment.version());
                });
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public WorkOrderResponse get(String equipmentId, String workOrderKey) {
        Equipment equipment = equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        WorkOrder order = workOrderRepository.findByKey(workOrderKey)
                .filter(found -> found.equipmentId().equals(equipmentId))
                .orElseThrow(() -> ApiException.notFound("WORK_ORDER_NOT_FOUND",
                        "工单不存在：" + workOrderKey));
        return toResponse(order, equipment.version());
    }

    @Transactional(readOnly = true)
    public List<WorkOrderResponse> list(String equipmentId) {
        Equipment equipment = equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return workOrderRepository.listByEquipment(equipmentId).stream()
                .map(order -> toResponse(order, equipment.version()))
                .toList();
    }

    // ---------- 内部规则 ----------

    /** 批量预校验：窗口、基线、唯一性与最终读数序列单调性；任一失败 422/409，不落任何数据。 */
    private void prevalidateBatch(String equipmentId, WorkOrder order, List<WorkOrderReadingItem> items) {
        List<Reading> existing = equipmentRepository.listReadings(equipmentId);
        Set<String> existingIds = existing.stream().map(Reading::readingId).collect(Collectors.toSet());
        Set<Instant> existingTimes = existing.stream().map(Reading::sampledAt).collect(Collectors.toSet());
        Set<String> batchIds = new HashSet<>();
        Set<Instant> batchTimes = new HashSet<>();
        for (WorkOrderReadingItem item : items) {
            if (item.sampledAt().isBefore(order.windowStart())
                    || !item.sampledAt().isBefore(order.windowEnd())) {
                throw ApiException.unprocessable("READING_OUT_OF_WINDOW",
                        "读表时刻不在工单允许窗口内：" + item.readingId());
            }
            if (item.cumulativeMinutes() < order.baselineCumulativeMinutes()) {
                throw ApiException.unprocessable("READING_BELOW_BASELINE",
                        "读数低于工单基线（" + order.baselineCumulativeMinutes() + "）：" + item.readingId());
            }
            if (existingIds.contains(item.readingId()) || !batchIds.add(item.readingId())) {
                throw ApiException.conflict("READING_EXISTS", "读数已存在：" + item.readingId());
            }
            if (existingTimes.contains(item.sampledAt()) || !batchTimes.add(item.sampledAt())) {
                throw ApiException.unprocessable("READING_TIME_DUPLICATE",
                        "同一设备同一采样时刻仅允许一条读数");
            }
        }
        // 设备最终读数序列：合并既有读数与本批读数后按采样时刻排序，累计工时须单调不减
        List<long[]> sequence = new ArrayList<>();
        existing.forEach(reading -> sequence.add(
                new long[]{reading.sampledAt().toEpochMilli(), reading.cumulativeMinutes()}));
        items.forEach(item -> sequence.add(
                new long[]{item.sampledAt().toEpochMilli(), item.cumulativeMinutes()}));
        sequence.sort(Comparator.comparingLong(pair -> pair[0]));
        for (int i = 1; i < sequence.size(); i++) {
            if (sequence.get(i)[1] < sequence.get(i - 1)[1]) {
                throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                        "批量登记后设备最终读数序列违反单调不减约束");
            }
        }
    }

    /** 读数摘要：按读数标识排序拼接，保证同批同参摘要稳定。 */
    private String readingsDigest(List<WorkOrderReadingItem> items) {
        return items.stream()
                .sorted(Comparator.comparing(WorkOrderReadingItem::readingId))
                .map(item -> item.readingId() + ":" + item.sampledAt() + ":" + item.cumulativeMinutes())
                .collect(Collectors.joining(";"));
    }

    /** 建单指纹：设备 + 工单版本(1) + 基线 + 窗口。 */
    private String createFingerprint(String equipmentId, CreateWorkOrderRequest req, WorkOrder existing) {
        String baseline;
        if (existing != null) {
            baseline = existing.baselineReadingId() + "|" + existing.baselineSampledAt()
                    + "|" + existing.baselineCumulativeMinutes();
        } else {
            baseline = equipmentRepository.findLatestReading(equipmentId)
                    .map(reading -> reading.readingId() + "|" + reading.sampledAt()
                            + "|" + reading.cumulativeMinutes())
                    .orElse("none");
        }
        return equipmentId + "|v1|" + baseline + "|" + req.windowStart() + "|" + req.windowEnd();
    }

    /** 状态变更指纹：设备 + 期望工单版本 + 冻结基线 + 冻结窗口。 */
    private String operationFingerprint(WorkOrder order, long expectedWorkOrderVersion) {
        return order.equipmentId() + "|wo-v" + expectedWorkOrderVersion + "|"
                + order.baselineReadingId() + "|" + order.baselineSampledAt() + "|"
                + order.baselineCumulativeMinutes() + "|" + order.windowStart() + "|" + order.windowEnd();
    }

    /** 工单写操作幂等键：workOrderKey + 操作类型 + 期望工单版本（版本推进后为新键，允许顺序多批）。 */
    static String idempotencyKey(String workOrderKey, String operation, long workOrderVersion) {
        return "WO|" + workOrderKey + "|" + operation + "|v" + workOrderVersion;
    }

    private WorkOrderResponse toResponse(WorkOrder order, long equipmentVersion) {
        int readingsInWindow = equipmentRepository.countReadingsInWindow(order.equipmentId(),
                order.windowStart(), order.windowEnd());
        Optional<Reading> lastValid = equipmentRepository.findLastValidReadingInWindow(order.equipmentId(),
                order.windowStart(), order.windowEnd(), order.baselineCumulativeMinutes());
        boolean cancellable = order.status() == WorkOrderStatus.CREATED;
        String cancelRestriction = switch (order.status()) {
            case CREATED -> "CANCEL_ALLOWED";
            case STARTED -> "ALREADY_STARTED";
            case CLOSED -> "ALREADY_CLOSED";
            case CANCELLED -> "ALREADY_CANCELLED";
            case TERMINATED -> "ALREADY_TERMINATED";
        };
        return new WorkOrderResponse(order.workOrderKey(), order.equipmentId(), order.status().name(),
                order.workOrderVersion(), order.baselineReadingId(), order.baselineSampledAt(),
                order.baselineCumulativeMinutes(), order.windowStart(), order.windowEnd(),
                order.startedAt(), order.closedAt(), order.cancelledAt(), order.terminatedAt(),
                order.snapshotLastReadingId(), order.snapshotLastSampledAt(),
                order.snapshotLastCumulativeMinutes(),
                readingsInWindow,
                lastValid.map(Reading::readingId).orElse(null),
                lastValid.map(Reading::sampledAt).orElse(null),
                lastValid.map(Reading::cumulativeMinutes).orElse(null),
                cancellable, cancelRestriction, equipmentVersion);
    }

    private WorkOrder copy(WorkOrder order, WorkOrderStatus status, long version,
                           Instant startedAt, Instant closedAt, Instant cancelledAt, Instant terminatedAt,
                           String snapshotLastReadingId, Instant snapshotLastSampledAt,
                           Long snapshotLastCumulativeMinutes, Instant updatedAt) {
        return new WorkOrder(order.workOrderKey(), order.equipmentId(), status, version,
                order.baselineReadingId(), order.baselineSampledAt(), order.baselineCumulativeMinutes(),
                order.windowStart(), order.windowEnd(), startedAt, closedAt, cancelledAt, terminatedAt,
                snapshotLastReadingId, snapshotLastSampledAt, snapshotLastCumulativeMinutes,
                order.createdAt(), updatedAt);
    }

    private Equipment lockEquipment(String equipmentId) {
        return equipmentRepository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
    }

    private WorkOrder lockWorkOrder(String equipmentId, String workOrderKey) {
        return workOrderRepository.findByKeyForUpdate(workOrderKey)
                .filter(order -> order.equipmentId().equals(equipmentId))
                .orElseThrow(() -> ApiException.notFound("WORK_ORDER_NOT_FOUND",
                        "工单不存在：" + workOrderKey));
    }

    private ApiException equipmentNotFound(String equipmentId) {
        return ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在：" + equipmentId);
    }

    private void checkEquipmentVersion(Equipment equipment, long expectedVersion) {
        if (equipment.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "设备版本冲突：期望 " + expectedVersion + "，当前 " + equipment.version());
        }
    }

    private void checkWorkOrderVersion(WorkOrder order, long expectedWorkOrderVersion) {
        if (order.workOrderVersion() != expectedWorkOrderVersion) {
            throw ApiException.conflict("WORK_ORDER_VERSION_CONFLICT",
                    "工单版本冲突：期望 " + expectedWorkOrderVersion
                            + "，当前 " + order.workOrderVersion());
        }
    }

    private void requireNotTerminal(WorkOrder order) {
        if (order.status() == WorkOrderStatus.CLOSED
                || order.status() == WorkOrderStatus.CANCELLED
                || order.status() == WorkOrderStatus.TERMINATED) {
            throw ApiException.conflict("WORK_ORDER_TERMINAL_STATE",
                    "工单已完结（" + order.status() + "），不允许再变更：" + order.workOrderKey());
        }
    }
}
