package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.IdempotencyRecord;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等执行器：在单个事务内“占键 → 执行业务 → 回填首次成功响应快照”。
 * 业务失败整体回滚，requestId 不被占用；同 requestId 同指纹重放返回首次完整响应快照，
 * 同 requestId 异指纹返回 409。
 */
@Service
public class IdempotencyExecutor {

    private final IdempotencyRepository idempotency;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public IdempotencyExecutor(IdempotencyRepository idempotency, ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager) {
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 幂等执行。requestId 首次出现：事务内占键并执行业务，成功后回填响应快照；
     * 业务抛异常则整体回滚不占键。requestId 已存在：同指纹返回首次快照，异指纹 409。
     */
    public <T> T idempotent(String requestId, String operation, String actor, String fingerprint,
                            Class<T> responseType, Supplier<T> business) {
        try {
            return transactions.execute(status -> {
                idempotency.insertNew(requestId, operation, actor, fingerprint, Instant.now());
                T result = business.get();
                idempotency.storeResponse(requestId, 200, writeJson(result));
                return result;
            });
        } catch (DuplicateKeyException ex) {
            return replay(requestId, fingerprint, responseType);
        }
    }

    /**
     * 重放：同指纹返回首次成功响应快照（读取不改变状态）；异指纹返回 409。
     */
    private <T> T replay(String requestId, String fingerprint, Class<T> responseType) {
        IdempotencyRecord record = idempotency.find(requestId)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 冲突且无法读取首次记录: " + requestId));
        if (!record.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已用于不同参数的请求: " + requestId);
        }
        try {
            return objectMapper.readValue(record.responseBody(), responseType);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等快照反序列化失败: " + requestId, ex);
        }
    }

    private String writeJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            throw new IllegalStateException("响应快照序列化失败", ex);
        }
    }
}
