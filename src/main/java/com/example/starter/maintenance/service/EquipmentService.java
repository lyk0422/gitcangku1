package com.example.starter.maintenance.service;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.BatchIsolationRequest;
import com.example.starter.maintenance.api.dto.BatchIsolationResponse;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.ConfirmLockPointRequest;
import com.example.starter.maintenance.api.dto.CreateIsolationRequest;
import com.example.starter.maintenance.api.dto.DeactivateEquipmentRequest;
import com.example.starter.maintenance.api.dto.EquipmentDeactivationResponse;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.FinalizeReleaseRequest;
import com.example.starter.maintenance.api.dto.IsolationDiagnosticsResponse;
import com.example.starter.maintenance.api.dto.IsolationEventView;
import com.example.starter.maintenance.api.dto.IsolationOrderResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReleaseLockPointRequest;
import com.example.starter.maintenance.api.dto.RejectedReadingView;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;

/**
 * 设备工时保养 + 能源隔离外观服务：委托事务服务执行；并发下唯一键冲突（事务已回滚）时，
 * 优先按 requestId 重放已提交的成功结果，否则转换为 409 业务冲突。
 */
@Service
public class EquipmentService {

    private final EquipmentTxService txService;
    private final IsolationTxService isolationTxService;
    private final IdempotencyService idempotency;
    private final FingerprintService fingerprints;

    public EquipmentService(EquipmentTxService txService, IsolationTxService isolationTxService,
                            IdempotencyService idempotency, FingerprintService fingerprints) {
        this.txService = txService;
        this.isolationTxService = isolationTxService;
        this.idempotency = idempotency;
        this.fingerprints = fingerprints;
    }

    public EquipmentResponse register(RegisterEquipmentRequest req) {
        String fingerprint = EquipmentTxService.registerFingerprint(req, fingerprints);
        return recoverDuplicateKey(req.requestId(), EquipmentTxService.OP_REGISTER_EQUIPMENT, fingerprint,
                EquipmentResponse.class, () -> txService.register(req));
    }

    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        return txService.addReading(equipmentId, req);
    }

    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        return txService.reviseReading(equipmentId, readingId, req);
    }

    public MaintenanceResponse completeMaintenance(String equipmentId, CompleteMaintenanceRequest req) {
        return txService.completeMaintenance(equipmentId, req);
    }

    public StatusResponse getStatus(String equipmentId) {
        return txService.getStatus(equipmentId);
    }

    public List<ReadingResponse> listReadings(String equipmentId) {
        return txService.listReadings(equipmentId);
    }

    public List<RevisionView> listRevisions(String equipmentId, String readingId) {
        return txService.listRevisions(equipmentId, readingId);
    }

    public List<MaintenanceResponse> listMaintenances(String equipmentId) {
        return txService.listMaintenances(equipmentId);
    }

    // ---------- 能源隔离 ----------

    public IsolationOrderResponse createIsolation(String equipmentId, CreateIsolationRequest req) {
        return isolationTxService.createIsolation(equipmentId, req);
    }

    public BatchIsolationResponse createBatchIsolation(BatchIsolationRequest req) {
        String fingerprint = IsolationTxService.batchFingerprint(req, isolationTxService.fingerprints());
        return recoverDuplicateKey(req.requestId(), IsolationTxService.OP_BATCH_CREATE_ISOLATION,
                fingerprint, BatchIsolationResponse.class,
                () -> isolationTxService.createBatchIsolation(req));
    }

    public IsolationOrderResponse confirmLockPoint(String equipmentId, String lockPointId,
                                                   ConfirmLockPointRequest req) {
        return isolationTxService.confirmLockPoint(equipmentId, lockPointId, req);
    }

    public IsolationOrderResponse releaseLockPoint(String equipmentId, String lockPointId,
                                                   ReleaseLockPointRequest req) {
        return isolationTxService.releaseLockPoint(equipmentId, lockPointId, req);
    }

    public IsolationOrderResponse finalizeRelease(String equipmentId, FinalizeReleaseRequest req) {
        return isolationTxService.finalizeRelease(equipmentId, req);
    }

    public EquipmentDeactivationResponse deactivateEquipment(String equipmentId,
                                                             DeactivateEquipmentRequest req) {
        return isolationTxService.deactivateEquipment(equipmentId, req);
    }

    public IsolationOrderResponse getIsolationOrder(String equipmentId, long orderId) {
        return isolationTxService.getOrder(equipmentId, orderId);
    }

    public List<IsolationOrderResponse> listIsolationOrders(String equipmentId) {
        return isolationTxService.listOrders(equipmentId);
    }

    public List<IsolationEventView> listIsolationEvents(String equipmentId) {
        return isolationTxService.listEvents(equipmentId);
    }

    public List<RejectedReadingView> listRejectedReadings(String equipmentId) {
        return isolationTxService.listRejectedReadings(equipmentId);
    }

    public IsolationDiagnosticsResponse isolationDiagnostics(String equipmentId, Instant windowFrom,
                                                             Instant windowTo) {
        return isolationTxService.diagnostics(equipmentId, windowFrom, windowTo);
    }

    /**
     * 并发唯一键冲突补偿：冲突事务已回滚，若同 requestId 的成功记录已提交则重放（异参抛 409），
     * 否则说明是业务唯一键冲突，返回 409。
     */
    private <T> T recoverDuplicateKey(String requestId, String operation, String fingerprint,
                                      Class<T> type, Supplier<T> action) {
        try {
            return action.get();
        } catch (DuplicateKeyException e) {
            T replayed = idempotency.replayExisting(requestId, operation, fingerprint, type);
            if (replayed != null) {
                return replayed;
            }
            throw ApiException.conflict("DUPLICATE_KEY", "唯一约束冲突，请核对业务标识是否已被占用");
        }
    }
}
