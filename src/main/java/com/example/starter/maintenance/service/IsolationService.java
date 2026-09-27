package com.example.starter.maintenance.service;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.BatchCreateIsolationRequest;
import com.example.starter.maintenance.api.dto.BatchCreateIsolationResponse;
import com.example.starter.maintenance.api.dto.ConfirmLockPointsRequest;
import com.example.starter.maintenance.api.dto.CreateIsolationRequest;
import com.example.starter.maintenance.api.dto.IsolationOrderResponse;
import com.example.starter.maintenance.api.dto.LockEventView;
import com.example.starter.maintenance.api.dto.ReleaseIsolationRequest;
import com.example.starter.maintenance.api.dto.RejectedReadingView;

/**
 * 能源隔离令外观服务：委托事务服务执行；并发下唯一键冲突（事务已回滚）时，
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
        return recoverDuplicateKey(req.requestId(), "CREATE_ISOLATION",
                IsolationTxService.createFingerprint(req),
                IsolationOrderResponse.class, () -> txService.createIsolation(equipmentId, req));
    }

    public BatchCreateIsolationResponse batchCreateIsolations(BatchCreateIsolationRequest req) {
        return recoverDuplicateKey(req.requestId(), "BATCH_CREATE_ISOLATION",
                IsolationTxService.batchFingerprint(req),
                BatchCreateIsolationResponse.class, () -> txService.batchCreateIsolations(req));
    }

    public IsolationOrderResponse confirmLockPoints(String equipmentId, String orderId,
                                                    ConfirmLockPointsRequest req) {
        return recoverDuplicateKey(req.requestId(), "CONFIRM_LOCK_POINTS",
                IsolationTxService.confirmFingerprint(req),
                IsolationOrderResponse.class,
                () -> txService.confirmLockPoints(equipmentId, orderId, req));
    }

    public IsolationOrderResponse releaseIsolation(String equipmentId, String orderId,
                                                   ReleaseIsolationRequest req) {
        return recoverDuplicateKey(req.requestId(), "RELEASE_ISOLATION",
                IsolationTxService.releaseFingerprint(req),
                IsolationOrderResponse.class,
                () -> txService.releaseIsolation(equipmentId, orderId, req));
    }

    public IsolationOrderResponse getOrder(String equipmentId, String orderId) {
        return txService.getOrderForEquipment(equipmentId, orderId);
    }

    public List<IsolationOrderResponse> listOrders(String equipmentId) {
        return txService.listOrdersForEquipment(equipmentId);
    }

    public List<LockEventView> listLockEvents(String orderId) {
        return txService.listLockEvents(orderId);
    }

    public List<RejectedReadingView> listRejectedReadings(String equipmentId) {
        return txService.listRejectedReadings(equipmentId);
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
