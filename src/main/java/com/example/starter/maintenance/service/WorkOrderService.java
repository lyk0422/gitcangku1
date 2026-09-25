package com.example.starter.maintenance.service;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.BatchReadingsResponse;
import com.example.starter.maintenance.api.dto.CancelEligibilityResponse;
import com.example.starter.maintenance.api.dto.CancelWorkOrderRequest;
import com.example.starter.maintenance.api.dto.CloseWorkOrderRequest;
import com.example.starter.maintenance.api.dto.CreateWorkOrderRequest;
import com.example.starter.maintenance.api.dto.MaintenanceSnapshotResponse;
import com.example.starter.maintenance.api.dto.RegisterWorkOrderReadingsRequest;
import com.example.starter.maintenance.api.dto.StartWorkOrderRequest;
import com.example.starter.maintenance.api.dto.TerminateWorkOrderRequest;
import com.example.starter.maintenance.api.dto.WorkOrderResponse;

/**
 * 保养工单外观服务：委托事务服务执行；并发唯一键冲突（事务已回滚）时，
 * 优先按 workOrderKey 与请求指纹重放已提交的成功结果，否则转换为 409 业务冲突。
 */
@Service
public class WorkOrderService {

    private static final String OP_CREATE = "CREATE_WORK_ORDER";
    private static final String OP_START = "START_WORK_ORDER";
    private static final String OP_REGISTER = "REGISTER_READINGS";
    private static final String OP_CLOSE = "CLOSE_WORK_ORDER";
    private static final String OP_CANCEL = "CANCEL_WORK_ORDER";
    private static final String OP_TERMINATE = "TERMINATE_WORK_ORDER";

    private final WorkOrderTxService txService;
    private final WorkOrderIdempotencyService idempotency;

    public WorkOrderService(WorkOrderTxService txService, WorkOrderIdempotencyService idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    public WorkOrderResponse createWorkOrder(String equipmentId, CreateWorkOrderRequest req) {
        String fingerprint = WorkOrderFingerprints.create(equipmentId, req.workOrderId(),
                req.expectedVersion(), req.baselineReadingId(),
                req.windowStart().toString(), req.windowEnd().toString());
        return recover(req.workOrderKey(), OP_CREATE, fingerprint, WorkOrderResponse.class,
                () -> txService.createWorkOrder(equipmentId, req));
    }

    public WorkOrderResponse startWorkOrder(String equipmentId, String workOrderId,
                                            StartWorkOrderRequest req) {
        String fingerprint = WorkOrderFingerprints.lifecycle(OP_START, equipmentId, workOrderId,
                req.expectedVersion(), req.workOrderVersion());
        return recover(req.workOrderKey(), OP_START, fingerprint, WorkOrderResponse.class,
                () -> txService.startWorkOrder(equipmentId, workOrderId, req));
    }

    public BatchReadingsResponse registerReadings(String equipmentId, String workOrderId,
                                                  RegisterWorkOrderReadingsRequest req) {
        String fingerprint = WorkOrderFingerprints.readings(equipmentId, workOrderId,
                req.expectedVersion(), req.workOrderVersion(), req.readings());
        return recover(req.workOrderKey(), OP_REGISTER, fingerprint, BatchReadingsResponse.class,
                () -> txService.registerReadings(equipmentId, workOrderId, req));
    }

    public MaintenanceSnapshotResponse closeWorkOrder(String equipmentId, String workOrderId,
                                                      CloseWorkOrderRequest req) {
        String fingerprint = WorkOrderFingerprints.lifecycle(OP_CLOSE, equipmentId, workOrderId,
                req.expectedVersion(), req.workOrderVersion());
        return recover(req.workOrderKey(), OP_CLOSE, fingerprint, MaintenanceSnapshotResponse.class,
                () -> txService.closeWorkOrder(equipmentId, workOrderId, req));
    }

    public WorkOrderResponse cancelWorkOrder(String equipmentId, String workOrderId,
                                             CancelWorkOrderRequest req) {
        String fingerprint = WorkOrderFingerprints.lifecycle(OP_CANCEL, equipmentId, workOrderId,
                req.expectedVersion(), req.workOrderVersion());
        return recover(req.workOrderKey(), OP_CANCEL, fingerprint, WorkOrderResponse.class,
                () -> txService.cancelWorkOrder(equipmentId, workOrderId, req));
    }

    public WorkOrderResponse terminateWorkOrder(String equipmentId, String workOrderId,
                                                TerminateWorkOrderRequest req) {
        String fingerprint = WorkOrderFingerprints.terminate(equipmentId, workOrderId,
                req.expectedVersion(), req.workOrderVersion(), req.reason());
        return recover(req.workOrderKey(), OP_TERMINATE, fingerprint, WorkOrderResponse.class,
                () -> txService.terminateWorkOrder(equipmentId, workOrderId, req));
    }

    public WorkOrderResponse getWorkOrder(String equipmentId, String workOrderId) {
        return txService.getWorkOrder(equipmentId, workOrderId);
    }

    public List<WorkOrderResponse> listWorkOrders(String equipmentId) {
        return txService.listWorkOrders(equipmentId);
    }

    public MaintenanceSnapshotResponse getSnapshot(String equipmentId, String workOrderId) {
        return txService.getSnapshot(equipmentId, workOrderId);
    }

    public CancelEligibilityResponse getCancelEligibility(String equipmentId, String workOrderId) {
        return txService.getCancelEligibility(equipmentId, workOrderId);
    }

    public List<com.example.starter.maintenance.api.dto.ReadingDiagnostic> diagnoseReadings(
            String equipmentId, String workOrderId) {
        return txService.diagnoseReadings(equipmentId, workOrderId);
    }

    /**
     * 并发唯一键冲突补偿：冲突事务已回滚，若同 workOrderKey 同指纹的成功记录已提交则重放，
     * 否则说明是业务唯一键冲突，返回 409。
     */
    private <T> T recover(String workOrderKey, String operation, String fingerprint,
                          Class<T> type, java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (DuplicateKeyException e) {
            T replayed = idempotency.replayExisting(workOrderKey, operation, fingerprint, type);
            if (replayed != null) {
                return replayed;
            }
            throw ApiException.conflict("DUPLICATE_KEY", "唯一约束冲突，请核对工单标识与幂等键");
        }
    }
}
