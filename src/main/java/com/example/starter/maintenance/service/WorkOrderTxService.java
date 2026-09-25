package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.BatchReadingsResponse;
import com.example.starter.maintenance.api.dto.CancelEligibilityResponse;
import com.example.starter.maintenance.api.dto.CancelWorkOrderRequest;
import com.example.starter.maintenance.api.dto.CloseWorkOrderRequest;
import com.example.starter.maintenance.api.dto.CreateWorkOrderRequest;
import com.example.starter.maintenance.api.dto.MaintenanceSnapshotResponse;
import com.example.starter.maintenance.api.dto.ReadingDiagnostic;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterWorkOrderReadingsRequest;
import com.example.starter.maintenance.api.dto.StartWorkOrderRequest;
import com.example.starter.maintenance.api.dto.TerminateWorkOrderRequest;
import com.example.starter.maintenance.api.dto.WorkOrderReadingItem;
import com.example.starter.maintenance.api.dto.WorkOrderResponse;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceSnapshot;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.domain.WorkOrder;
import com.example.starter.maintenance.store.EquipmentRepository;
import com.example.starter.maintenance.store.WorkOrderRepository;

/**
 * 保养工单事务业务服务。写操作流程：设备行锁 → workOrderKey 幂等判定 →
 * 设备/工单版本校验 → 状态机与窗口规则 → 变更与版本推进。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚，不占键、不留半成品。
 */
@Service
public class WorkOrderTxService {

    private static final String OP_CREATE = "CREATE_WORK_ORDER";
    private static final String OP_START = "START_WORK_ORDER";
    private static final String OP_REGISTER = "REGISTER_READINGS";
    private static final String OP_CLOSE = "CLOSE_WORK_ORDER";
    private static final String OP_CANCEL = "CANCEL_WORK_ORDER";
    private static final String OP_TERMINATE = "TERMINATE_WORK_ORDER";

    private final EquipmentRepository equipmentRepository;
    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderIdempotencyService idempotency;
    private final Clock clock;

    public WorkOrderTxService(EquipmentRepository equipmentRepository,
                              WorkOrderRepository workOrderRepository,
                              WorkOrderIdempotencyService idempotency, Clock clock) {
        this.equipmentRepository = equipmentRepository;
        this.workOrderRepository = workOrderRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------- 创建工单 ----------

    @Transactional
    public WorkOrderResponse createWorkOrder(String equipmentId, CreateWorkOrderRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = WorkOrderFingerprints.create(equipmentId, req.workOrderId(),
                req.expectedVersion(), req.baselineReadingId(),
                req.windowStart().toString(), req.windowEnd().toString());
        return idempotency.execute(req.workOrderKey(), OP_CREATE, fingerprint,
                WorkOrderResponse.class, () -> {
                    checkEquipmentVersion(equipment, req.expectedVersion());
                    if (!req.windowEnd().isAfter(req.windowStart())) {
                        throw ApiException.unprocessable("WINDOW_INVALID",
                                "登记窗口结束时刻必须严格晚于开始时刻");
                    }
                    if (workOrderRepository.findWorkOrder(equipmentId, req.workOrderId()).isPresent()) {
                        throw ApiException.conflict("WORK_ORDER_EXISTS",
                                "工单已存在：" + req.workOrderId());
                    }
                    if (workOrderRepository.findOpenWorkOrder(equipmentId).isPresent()) {
                        throw ApiException.conflict("WORK_ORDER_OPEN",
                                "设备存在未终结工单，须先关闭、取消或终止后再建单");
                    }
                    Reading baseline = equipmentRepository.findReading(equipmentId, req.baselineReadingId())
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "基线读数不存在：" + req.baselineReadingId()));
                    if (!baseline.certified()) {
                        throw ApiException.unprocessable("BASELINE_NOT_CERTIFIED",
                                "基线读数须为设备当前已认证读数：" + req.baselineReadingId());
                    }
                    Instant now = clock.instant();
                    WorkOrder order = new WorkOrder(
                            req.workOrderId(), equipmentId, 1L, WorkOrder.CREATED,
                            baseline.readingId(), baseline.revisionNo(), baseline.sampledAt(),
                            baseline.cumulativeMinutes(), req.windowStart(), req.windowEnd(),
                            baseline.readingId(), baseline.sampledAt(), baseline.cumulativeMinutes(),
                            baseline.revisionNo(), now, null, null, null, null, null);
                    workOrderRepository.insertWorkOrder(order, now);
                    equipmentRepository.incrementVersion(equipmentId);
                    return toResponse(order, equipment.version() + 1);
                });
    }

    // ---------- 开始工单 ----------

    @Transactional
    public WorkOrderResponse startWorkOrder(String equipmentId, String workOrderId,
                                            StartWorkOrderRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        WorkOrder order = lockWorkOrder(equipmentId, workOrderId);
        String fingerprint = WorkOrderFingerprints.lifecycle(OP_START, equipmentId, workOrderId,
                req.expectedVersion(), req.workOrderVersion());
        return idempotency.execute(req.workOrderKey(), OP_START, fingerprint,
                WorkOrderResponse.class, () -> {
                    checkEquipmentVersion(equipment, req.expectedVersion());
                    checkWorkOrderVersion(order, req.workOrderVersion());
                    if (!WorkOrder.CREATED.equals(order.status())) {
                        throw ApiException.conflict("WORK_ORDER_NOT_CREATED",
                                "仅未开始工单可开始，当前状态：" + order.status());
                    }
                    Instant now = clock.instant();
                    int rows = workOrderRepository.markStarted(
                            equipmentId, workOrderId, order.version(), now);
                    if (rows == 0) {
                        throw ApiException.conflict("WORK_ORDER_STATUS_CONFLICT",
                                "工单状态已变化，开始失败");
                    }
                    equipmentRepository.incrementVersion(equipmentId);
                    return toResponse(
                            workOrderRepository.findWorkOrder(equipmentId, workOrderId).orElseThrow(),
                            equipment.version() + 1);
                });
    }

    // ---------- 工单期内批量登记读数 ----------

    @Transactional
    public BatchReadingsResponse registerReadings(String equipmentId, String workOrderId,
                                                  RegisterWorkOrderReadingsRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        WorkOrder order = lockWorkOrder(equipmentId, workOrderId);
        String fingerprint = WorkOrderFingerprints.readings(equipmentId, workOrderId,
                req.expectedVersion(), req.workOrderVersion(), req.readings());
        return idempotency.execute(req.workOrderKey(), OP_REGISTER, fingerprint,
                BatchReadingsResponse.class, () -> {
                    // 批量登记预校验阶段的版本失配按题意返回 422
                    if (equipment.version() != req.expectedVersion()) {
                        throw ApiException.unprocessable("WORK_ORDER_VERSION_CONFLICT",
                                "设备版本失配：期望 " + req.expectedVersion()
                                        + "，当前 " + equipment.version());
                    }
                    if (order.version() != req.workOrderVersion()) {
                        throw ApiException.unprocessable("WORK_ORDER_VERSION_CONFLICT",
                                "工单版本失配：期望 " + req.workOrderVersion()
                                        + "，当前 " + order.version());
                    }
                    if (!WorkOrder.IN_PROGRESS.equals(order.status())) {
                        throw ApiException.conflict("WORK_ORDER_NOT_IN_PROGRESS",
                                "仅进行中工单可登记读数，当前状态：" + order.status());
                    }
                    prevalidateBatch(order, req.readings());

                    Instant now = clock.instant();
                    for (WorkOrderReadingItem item : req.readings()) {
                        equipmentRepository.insertReading(
                                new Reading(equipmentId, item.readingId(), item.sampledAt(),
                                        item.cumulativeMinutes(), 1, false),
                                now);
                        equipmentRepository.insertRevision(equipmentId, item.readingId(), 1,
                                item.cumulativeMinutes(), req.workOrderKey(), now);
                    }
                    // 最近有效读数：工单当前 lastValid 与本批按采样时刻最晚者取更晚
                    WorkOrderReadingItem latestInBatch = req.readings().stream()
                            .max((a, b) -> a.sampledAt().compareTo(b.sampledAt()))
                            .orElseThrow();
                    Instant currentLastSampled = order.lastValidSampledAt();
                    if (currentLastSampled == null || !latestInBatch.sampledAt().isBefore(currentLastSampled)) {
                        workOrderRepository.updateLastValidReading(equipmentId, workOrderId,
                                latestInBatch.readingId(), latestInBatch.sampledAt(),
                                latestInBatch.cumulativeMinutes(), 1);
                    }
                    equipmentRepository.incrementVersion(equipmentId);

                    List<ReadingResponse> accepted = req.readings().stream()
                            .sorted((a, b) -> a.sampledAt().compareTo(b.sampledAt()))
                            .map(item -> new ReadingResponse(equipmentId, item.readingId(),
                                    item.sampledAt(), item.cumulativeMinutes(), 1, false, false,
                                    equipment.version() + 1))
                            .toList();
                    WorkOrder updated = workOrderRepository.findWorkOrder(equipmentId, workOrderId)
                            .orElseThrow();
                    return new BatchReadingsResponse(workOrderId, equipmentId,
                            req.readings().size(), accepted,
                            equipment.version() + 1, updated.version());
                });
    }

    // ---------- 关闭工单 ----------

    @Transactional
    public MaintenanceSnapshotResponse closeWorkOrder(String equipmentId, String workOrderId,
                                                      CloseWorkOrderRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        WorkOrder order = lockWorkOrder(equipmentId, workOrderId);
        String fingerprint = WorkOrderFingerprints.lifecycle(OP_CLOSE, equipmentId, workOrderId,
                req.expectedVersion(), req.workOrderVersion());
        return idempotency.execute(req.workOrderKey(), OP_CLOSE, fingerprint,
                MaintenanceSnapshotResponse.class, () -> {
                    checkEquipmentVersion(equipment, req.expectedVersion());
                    checkWorkOrderVersion(order, req.workOrderVersion());
                    if (WorkOrder.CLOSED.equals(order.status())) {
                        // 已关闭：幂等层未命中说明是异键重复关闭，返回既有快照并报冲突
                        throw ApiException.conflict("WORK_ORDER_ALREADY_CLOSED",
                                "工单已关闭，快照不可变");
                    }
                    if (!WorkOrder.CREATED.equals(order.status())
                            && !WorkOrder.IN_PROGRESS.equals(order.status())) {
                        throw ApiException.conflict("WORK_ORDER_NOT_CLOSABLE",
                                "仅未开始或进行中工单可关闭，当前状态：" + order.status());
                    }
                    Instant now = clock.instant();
                    int rows = workOrderRepository.compareAndUpdateStatus(
                            equipmentId, workOrderId, order.status(), order.version(),
                            WorkOrder.CLOSED, "closed_at", now);
                    if (rows == 0) {
                        throw ApiException.conflict("WORK_ORDER_STATUS_CONFLICT",
                                "工单状态已变化，关闭失败");
                    }
                    MaintenanceSnapshot snapshot = new MaintenanceSnapshot(
                            0L, workOrderId, equipmentId,
                            order.baselineReadingId(), order.baselineRevisionNo(),
                            order.baselineSampledAt(), order.baselineCumulativeMinutes(),
                            order.lastValidReadingId(),
                            order.lastValidRevisionNo() == null
                                    ? order.baselineRevisionNo() : order.lastValidRevisionNo(),
                            order.lastValidSampledAt() == null
                                    ? order.baselineSampledAt() : order.lastValidSampledAt(),
                            order.lastValidCumulativeMinutes() == null
                                    ? order.baselineCumulativeMinutes()
                                    : order.lastValidCumulativeMinutes(),
                            now);
                    long snapshotId = workOrderRepository.insertSnapshot(snapshot);
                    equipmentRepository.incrementVersion(equipmentId);
                    return new MaintenanceSnapshotResponse(
                            snapshotId, workOrderId, equipmentId,
                            snapshot.baselineReadingId(), snapshot.baselineRevisionNo(),
                            snapshot.baselineSampledAt(), snapshot.baselineCumulativeMinutes(),
                            snapshot.lastValidReadingId(), snapshot.lastValidRevisionNo(),
                            snapshot.lastValidSampledAt(), snapshot.lastValidCumulativeMinutes(),
                            now);
                });
    }

    // ---------- 取消工单 ----------

    @Transactional
    public WorkOrderResponse cancelWorkOrder(String equipmentId, String workOrderId,
                                             CancelWorkOrderRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        WorkOrder order = lockWorkOrder(equipmentId, workOrderId);
        String fingerprint = WorkOrderFingerprints.lifecycle(OP_CANCEL, equipmentId, workOrderId,
                req.expectedVersion(), req.workOrderVersion());
        return idempotency.execute(req.workOrderKey(), OP_CANCEL, fingerprint,
                WorkOrderResponse.class, () -> {
                    checkEquipmentVersion(equipment, req.expectedVersion());
                    checkWorkOrderVersion(order, req.workOrderVersion());
                    if (WorkOrder.IN_PROGRESS.equals(order.status())) {
                        throw ApiException.conflict("WORK_ORDER_ALREADY_STARTED",
                                "已开始工单不可取消，须关闭或终止");
                    }
                    if (!WorkOrder.CREATED.equals(order.status())) {
                        throw ApiException.conflict("WORK_ORDER_NOT_CANCELLABLE",
                                "仅未开始工单可取消，当前状态：" + order.status());
                    }
                    Instant now = clock.instant();
                    int rows = workOrderRepository.compareAndUpdateStatus(
                            equipmentId, workOrderId, WorkOrder.CREATED, order.version(),
                            WorkOrder.CANCELLED, "cancelled_at", now);
                    if (rows == 0) {
                        throw ApiException.conflict("WORK_ORDER_STATUS_CONFLICT",
                                "工单状态已变化，取消失败");
                    }
                    equipmentRepository.incrementVersion(equipmentId);
                    return toResponse(
                            workOrderRepository.findWorkOrder(equipmentId, workOrderId).orElseThrow(),
                            equipment.version() + 1);
                });
    }

    // ---------- 终止工单 ----------

    @Transactional
    public WorkOrderResponse terminateWorkOrder(String equipmentId, String workOrderId,
                                                TerminateWorkOrderRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        WorkOrder order = lockWorkOrder(equipmentId, workOrderId);
        String fingerprint = WorkOrderFingerprints.terminate(equipmentId, workOrderId,
                req.expectedVersion(), req.workOrderVersion(), req.reason());
        return idempotency.execute(req.workOrderKey(), OP_TERMINATE, fingerprint,
                WorkOrderResponse.class, () -> {
                    checkEquipmentVersion(equipment, req.expectedVersion());
                    checkWorkOrderVersion(order, req.workOrderVersion());
                    if (!WorkOrder.IN_PROGRESS.equals(order.status())) {
                        throw ApiException.conflict("WORK_ORDER_NOT_TERMINABLE",
                                "仅进行中工单可终止，当前状态：" + order.status());
                    }
                    Instant now = clock.instant();
                    int rows = workOrderRepository.markTerminated(
                            equipmentId, workOrderId, order.version(), req.reason(), now);
                    if (rows == 0) {
                        throw ApiException.conflict("WORK_ORDER_STATUS_CONFLICT",
                                "工单状态已变化，终止失败");
                    }
                    equipmentRepository.incrementVersion(equipmentId);
                    return toResponse(
                            workOrderRepository.findWorkOrder(equipmentId, workOrderId).orElseThrow(),
                            equipment.version() + 1);
                });
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public WorkOrderResponse getWorkOrder(String equipmentId, String workOrderId) {
        equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        WorkOrder order = lockWorkOrder(equipmentId, workOrderId);
        long equipmentVersion = equipmentRepository.findEquipment(equipmentId).orElseThrow().version();
        return toResponse(order, equipmentVersion);
    }

    @Transactional(readOnly = true)
    public List<WorkOrderResponse> listWorkOrders(String equipmentId) {
        Equipment equipment = equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return workOrderRepository.listWorkOrders(equipmentId).stream()
                .map(order -> toResponse(order, equipment.version()))
                .toList();
    }

    @Transactional(readOnly = true)
    public MaintenanceSnapshotResponse getSnapshot(String equipmentId, String workOrderId) {
        equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        workOrderRepository.findWorkOrder(equipmentId, workOrderId)
                .orElseThrow(() -> workOrderNotFound(workOrderId));
        MaintenanceSnapshot snapshot = workOrderRepository.findSnapshot(equipmentId, workOrderId)
                .orElseThrow(() -> ApiException.notFound("SNAPSHOT_NOT_FOUND",
                        "工单尚未关闭，不存在保养状态快照：" + workOrderId));
        return new MaintenanceSnapshotResponse(
                snapshot.snapshotId(), snapshot.workOrderId(), snapshot.equipmentId(),
                snapshot.baselineReadingId(), snapshot.baselineRevisionNo(),
                snapshot.baselineSampledAt(), snapshot.baselineCumulativeMinutes(),
                snapshot.lastValidReadingId(), snapshot.lastValidRevisionNo(),
                snapshot.lastValidSampledAt(), snapshot.lastValidCumulativeMinutes(),
                snapshot.closedAt());
    }

    @Transactional(readOnly = true)
    public CancelEligibilityResponse getCancelEligibility(String equipmentId, String workOrderId) {
        equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        WorkOrder order = lockWorkOrder(equipmentId, workOrderId);
        boolean cancellable = WorkOrder.CREATED.equals(order.status());
        String reason = cancellable ? null : switch (order.status()) {
            case WorkOrder.IN_PROGRESS -> "已开始工单不可取消，须关闭或终止";
            case WorkOrder.CLOSED -> "工单已关闭";
            case WorkOrder.CANCELLED -> "工单已取消";
            case WorkOrder.TERMINATED -> "工单已终止";
            default -> "当前状态不可取消：" + order.status();
        };
        return new CancelEligibilityResponse(workOrderId, order.status(), cancellable, reason);
    }

    /** 读数诊断：基于当前库内数据，逐条判定窗口内读数是否越窗、低于基线或相对相邻读数倒退。 */
    @Transactional(readOnly = true)
    public List<ReadingDiagnostic> diagnoseReadings(String equipmentId, String workOrderId) {
        equipmentRepository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        WorkOrder order = lockWorkOrder(equipmentId, workOrderId);
        List<Reading> all = equipmentRepository.listReadings(equipmentId);
        List<ReadingDiagnostic> diagnostics = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Reading r = all.get(i);
            if (r.sampledAt().isBefore(order.windowStart())
                    || !r.sampledAt().isBefore(order.windowEnd())) {
                continue;
            }
            String reasonCode = null;
            String message = null;
            if (r.cumulativeMinutes() < order.baselineCumulativeMinutes()) {
                reasonCode = "BELOW_BASELINE";
                message = "累计工时低于基线 " + order.baselineCumulativeMinutes();
            } else if (i > 0 && r.cumulativeMinutes() < all.get(i - 1).cumulativeMinutes()) {
                reasonCode = "READING_ORDER_VIOLATION";
                message = "累计工时小于前一条读数，违反单调不减约束";
            } else if (i + 1 < all.size()
                    && r.cumulativeMinutes() > all.get(i + 1).cumulativeMinutes()) {
                reasonCode = "READING_ORDER_VIOLATION";
                message = "累计工时大于后一条读数，违反单调不减约束";
            }
            diagnostics.add(new ReadingDiagnostic(r.readingId(), reasonCode == null,
                    reasonCode, message));
        }
        return diagnostics;
    }

    // ---------- 内部规则 ----------

    /**
     * 批量登记整体预校验：任何一条不满足都抛 422（在任何写入之前执行，故全部回滚无半成品）。
     * 校验：批次内标识/时刻不重复、不与库内冲突、落在左闭右开窗口、不低于基线、
     * 合并库内既有读数形成最终序列后累计分钟单调不减。
     */
    private void prevalidateBatch(WorkOrder order, List<WorkOrderReadingItem> items) {
        Set<String> batchIds = new HashSet<>();
        Set<Instant> batchTimes = new HashSet<>();
        for (WorkOrderReadingItem item : items) {
            if (!batchIds.add(item.readingId())) {
                throw unprocessableItem(item.readingId(), "READING_ID_DUPLICATE",
                        "批次内读数标识重复");
            }
            if (!batchTimes.add(item.sampledAt())) {
                throw unprocessableItem(item.readingId(), "READING_TIME_DUPLICATE",
                        "批次内存在相同采样时刻的读数");
            }
            if (equipmentRepository.findReading(order.equipmentId(), item.readingId()).isPresent()) {
                throw unprocessableItem(item.readingId(), "READING_ID_DUPLICATE",
                        "读数标识已存在");
            }
            if (equipmentRepository.findReadingAt(order.equipmentId(), item.sampledAt()).isPresent()) {
                throw unprocessableItem(item.readingId(), "READING_TIME_DUPLICATE",
                        "同一设备同一采样时刻已存在读数");
            }
            if (item.sampledAt().isBefore(order.windowStart())
                    || !item.sampledAt().isBefore(order.windowEnd())) {
                throw unprocessableItem(item.readingId(), "OUT_OF_WINDOW",
                        "读表时刻不在允许登记窗口 [" + order.windowStart() + ", "
                                + order.windowEnd() + ") 内");
            }
            if (item.cumulativeMinutes() < order.baselineCumulativeMinutes()) {
                throw unprocessableItem(item.readingId(), "BELOW_BASELINE",
                        "读数不得低于基线工时 " + order.baselineCumulativeMinutes());
            }
        }
        // 合并库内既有读数与本批读数，按采样时刻排序验证最终序列单调不减
        Map<Instant, Long> merged = new HashMap<>();
        for (Reading r : equipmentRepository.listReadings(order.equipmentId())) {
            merged.put(r.sampledAt(), r.cumulativeMinutes());
        }
        for (WorkOrderReadingItem item : items) {
            merged.put(item.sampledAt(), item.cumulativeMinutes());
        }
        List<Map.Entry<Instant, Long>> sequence = merged.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        for (int i = 1; i < sequence.size(); i++) {
            if (sequence.get(i).getValue() < sequence.get(i - 1).getValue()) {
                Instant at = sequence.get(i).getKey();
                String readingId = items.stream()
                        .filter(it -> it.sampledAt().equals(at))
                        .map(WorkOrderReadingItem::readingId)
                        .findFirst()
                        .orElseGet(() -> "采样时刻 " + at);
                throw unprocessableItem(readingId, "READING_ORDER_VIOLATION",
                        "最终读数序列中累计工时相对前一条倒退，违反单调不减约束");
            }
        }
    }

    private ApiException unprocessableItem(String readingId, String code, String message) {
        return ApiException.unprocessable(code, "读数 " + readingId + "：" + message);
    }

    private Equipment lockEquipment(String equipmentId) {
        return equipmentRepository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
    }

    private WorkOrder lockWorkOrder(String equipmentId, String workOrderId) {
        return workOrderRepository.findWorkOrder(equipmentId, workOrderId)
                .orElseThrow(() -> workOrderNotFound(workOrderId));
    }

    private ApiException equipmentNotFound(String equipmentId) {
        return ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在：" + equipmentId);
    }

    private ApiException workOrderNotFound(String workOrderId) {
        return ApiException.notFound("WORK_ORDER_NOT_FOUND", "工单不存在：" + workOrderId);
    }

    private void checkEquipmentVersion(Equipment equipment, long expectedVersion) {
        if (equipment.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "设备版本冲突：期望 " + expectedVersion + "，当前 " + equipment.version());
        }
    }

    private void checkWorkOrderVersion(WorkOrder order, long expectedVersion) {
        if (order.version() != expectedVersion) {
            throw ApiException.conflict("WORK_ORDER_VERSION_CONFLICT",
                    "工单版本冲突：期望 " + expectedVersion + "，当前 " + order.version());
        }
    }

    private WorkOrderResponse toResponse(WorkOrder order, long equipmentVersion) {
        return new WorkOrderResponse(
                order.workOrderId(), order.equipmentId(), order.version(), order.status(),
                order.baselineReadingId(), order.baselineRevisionNo(),
                order.baselineSampledAt(), order.baselineCumulativeMinutes(),
                order.windowStart(), order.windowEnd(),
                order.lastValidReadingId(), order.lastValidSampledAt(),
                order.lastValidCumulativeMinutes(), order.lastValidRevisionNo(),
                order.createdAt(), order.startedAt(), order.closedAt(),
                order.cancelledAt(), order.terminatedAt(), order.terminateReason(),
                equipmentVersion);
    }
}
