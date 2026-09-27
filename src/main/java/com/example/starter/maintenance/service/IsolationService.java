package com.example.starter.maintenance.service;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.BatchCreateIsolationRequest;
import com.example.starter.maintenance.api.dto.BatchIsolationResponse;
import com.example.starter.maintenance.api.dto.ConfirmLockPointRequest;
import com.example.starter.maintenance.api.dto.CreateIsolationRequest;
import com.example.starter.maintenance.api.dto.IsolationHistoryEvent;
import com.example.starter.maintenance.api.dto.IsolationOrderResponse;
import com.example.starter.maintenance.api.dto.IsolatedReadingRejectionView;
import com.example.starter.maintenance.api.dto.ReleaseIsolationRequest;

/**
 * 能源隔离外观服务：委托事务服务执行；并发唯一键冲突（事务已回滚）时，
 * 优先按 requestId 重放已提交的成功结果，否则转换为 409 业务冲突。
 */
@Service
public class IsolationService {

    private final IsolationTxService txService;
    private final IdempotencyService idempotency;

    public IsolationService(IsolationTxService txService, IdempotencyService idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    public IsolationOrderResponse createIsolation(String equipmentId, CreateIsolationRequest req) {
        String fingerprint = txService.createFingerprint(equipmentId, req);
        return recoverDuplicateKey(req.requestId(), "CREATE_ISOLATION", fingerprint,
                IsolationOrderResponse.class, () -> txService.createIsolation(equipmentId, req));
    }

    public BatchIsolationResponse createBatch(BatchCreateIsolationRequest req) {
        String fingerprint = txService.batchFingerprint(req);
        return recoverDuplicateKey(req.requestId(), "BATCH_CREATE_ISOLATION", fingerprint,
                BatchIsolationResponse.class, () -> txService.createBatch(req));
    }

    public IsolationOrderResponse confirmLockPoint(String orderId, ConfirmLockPointRequest req) {
        return txService.confirmLockPoint(orderId, req);
    }

    public IsolationOrderResponse release(String orderId, ReleaseIsolationRequest req) {
        return txService.release(orderId, req);
    }

    public IsolationOrderResponse getOrder(String orderId) {
        return txService.getOrder(orderId);
    }

    public List<IsolationOrderResponse> listOrders(String equipmentId) {
        return txService.listOrders(equipmentId);
    }

    public List<IsolationHistoryEvent> history(String equipmentId) {
        return txService.history(equipmentId);
    }

    public List<IsolatedReadingRejectionView> rejections(String equipmentId) {
        return txService.rejections(equipmentId);
    }

    /**
     * 并发唯一键冲突补偿：冲突事务已回滚，若同 requestId 的成功记录已提交则重放（异参抛 409），
     * 否则返回 409。
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
