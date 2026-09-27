package com.example.starter.maintenance.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.dto.BatchIsolationRequest;
import com.example.starter.maintenance.api.dto.BatchIsolationResponse;
import com.example.starter.maintenance.api.dto.ConfirmIsolationRequest;
import com.example.starter.maintenance.api.dto.CreateIsolationRequest;
import com.example.starter.maintenance.api.dto.DecommissionEquipmentRequest;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.IsolationResponse;
import com.example.starter.maintenance.api.dto.RejectedReadingView;
import com.example.starter.maintenance.api.dto.ReleaseLockPointRequest;

/**
 * 设备能源隔离外观服务：委托事务服务执行；并发唯一键冲突（事务已回滚）时，
 * 优先按 requestId 重放已提交的成功结果快照，否则转换为 409。查询直接透传，不改变状态。
 */
@Service
public class IsolationService {

    private final IsolationTxService txService;
    private final IdempotencyService idempotency;

    public IsolationService(IsolationTxService txService, IdempotencyService idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    public IsolationResponse createIsolation(String equipmentId, CreateIsolationRequest req) {
        return txService.createIsolation(equipmentId, req);
    }

    public BatchIsolationResponse batchCreateIsolations(BatchIsolationRequest req) {
        Map<String, Object> fingerprintParams = new HashMap<>();
        fingerprintParams.put("operatorId", req.operatorId());
        fingerprintParams.put("items", req.items());
        String fingerprint = FingerprintUtil.canonical(fingerprintParams);
        return idempotency.executeWithRecovery(req.requestId(), "BATCH_ISOLATE", fingerprint,
                BatchIsolationResponse.class, () -> txService.batchCreateIsolations(req));
    }

    public IsolationResponse confirmIsolation(String equipmentId, long isolationId,
                                              ConfirmIsolationRequest req) {
        return txService.confirmIsolation(equipmentId, isolationId, req);
    }

    public IsolationResponse releaseLockPoint(String equipmentId, long isolationId, String lockPointId,
                                              ReleaseLockPointRequest req) {
        return txService.releaseLockPoint(equipmentId, isolationId, lockPointId, req);
    }

    public EquipmentResponse decommission(String equipmentId, DecommissionEquipmentRequest req) {
        return txService.decommission(equipmentId, req);
    }

    public IsolationResponse getIsolation(String equipmentId, long isolationId) {
        return txService.getIsolation(equipmentId, isolationId);
    }

    public List<IsolationResponse> listIsolations(String equipmentId) {
        return txService.listIsolations(equipmentId);
    }

    public List<RejectedReadingView> listRejectedReadings(String equipmentId) {
        return txService.listRejectedReadings(equipmentId);
    }
}
