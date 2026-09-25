package com.example.starter.maintenance.service;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.CreateWorkOrderRequest;
import com.example.starter.maintenance.api.dto.RegisterWorkOrderReadingsRequest;
import com.example.starter.maintenance.api.dto.WorkOrderOperationRequest;
import com.example.starter.maintenance.api.dto.WorkOrderResponse;

/**
 * 保养工单外观服务：委托事务服务执行；并发下同 workOrderKey 唯一键冲突（事务已回滚）时，
 * 重新按既有工单与幂等记录裁决（同键同参重放，异参 409）。
 */
@Service
public class WorkOrderService {

    private final WorkOrderTxService txService;

    public WorkOrderService(WorkOrderTxService txService) {
        this.txService = txService;
    }

    public WorkOrderResponse create(String equipmentId, CreateWorkOrderRequest req) {
        try {
            return txService.create(equipmentId, req);
        } catch (DuplicateKeyException e) {
            // 并发同键建单：冲突事务已回滚，对方已提交；重走一次由幂等记录重放或判 409
            try {
                return txService.create(equipmentId, req);
            } catch (DuplicateKeyException again) {
                throw ApiException.conflict("WORK_ORDER_KEY_CONFLICT",
                        "workOrderKey 已被占用：" + req.workOrderKey());
            }
        }
    }

    public WorkOrderResponse start(String equipmentId, String workOrderKey, WorkOrderOperationRequest req) {
        return txService.start(equipmentId, workOrderKey, req);
    }

    public WorkOrderResponse registerReadings(String equipmentId, String workOrderKey,
                                              RegisterWorkOrderReadingsRequest req) {
        return txService.registerReadings(equipmentId, workOrderKey, req);
    }

    public WorkOrderResponse close(String equipmentId, String workOrderKey, WorkOrderOperationRequest req) {
        return txService.close(equipmentId, workOrderKey, req);
    }

    public WorkOrderResponse cancel(String equipmentId, String workOrderKey, WorkOrderOperationRequest req) {
        return txService.cancel(equipmentId, workOrderKey, req);
    }

    public WorkOrderResponse terminate(String equipmentId, String workOrderKey, WorkOrderOperationRequest req) {
        return txService.terminate(equipmentId, workOrderKey, req);
    }

    public WorkOrderResponse get(String equipmentId, String workOrderKey) {
        return txService.get(equipmentId, workOrderKey);
    }

    public List<WorkOrderResponse> list(String equipmentId) {
        return txService.list(equipmentId);
    }
}
