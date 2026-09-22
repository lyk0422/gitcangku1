package com.example.starter.service;

import com.example.starter.dao.IdempotencyDao;
import com.example.starter.error.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 写操作幂等执行器：在同一事务内完成占键、业务变更与成功响应落库。
 * 同键同参重放原成功结果；同键异参返回 409；业务失败整体回滚，不占键。
 */
@Component
public class IdempotentExecutor {

    private final TransactionTemplate transactionTemplate;
    private final IdempotencyDao idempotencyDao;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotentExecutor(TransactionTemplate transactionTemplate,
                              IdempotencyDao idempotencyDao,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.transactionTemplate = transactionTemplate;
        this.idempotencyDao = idempotencyDao;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 以幂等方式执行写操作。
     *
     * @param requestId    全局唯一幂等请求标识
     * @param action       写操作类型
     * @param fingerprint  请求参数指纹（含操作类型与全部业务参数）
     * @param responseType 响应类型，用于重放时反序列化
     * @param business     业务逻辑，仅在键未被占用时执行
     * @return 本次执行结果或重放的原成功结果
     */
    public <T> T execute(String requestId, String action, String fingerprint,
                         Class<T> responseType, Supplier<T> business) {
        try {
            return transactionTemplate.execute(status -> {
                var existing = idempotencyDao.find(requestId);
                if (existing.isPresent()) {
                    IdempotencyDao.IdempotencyRecord record = existing.get();
                    if (!record.fingerprint().equals(fingerprint)) {
                        throw new ConflictException("IDEMPOTENCY_CONFLICT",
                                "requestId 已被使用且请求参数不一致");
                    }
                    return deserialize(record.responseBody(), responseType);
                }
                idempotencyDao.insert(requestId, action, fingerprint, Instant.now(clock));
                T result = business.get();
                idempotencyDao.complete(requestId, serialize(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            // 并发同键：主键冲突在对方事务提交后抛出，读取已提交结果重放。
            var record = idempotencyDao.find(requestId);
            if (record.isEmpty()) {
                // 非幂等键引起的重复键（如业务主键冲突），交由调用方处理。
                throw e;
            }
            if (!record.get().fingerprint().equals(fingerprint)) {
                throw new ConflictException("IDEMPOTENCY_CONFLICT",
                        "requestId 已被使用且请求参数不一致");
            }
            return deserialize(record.get().responseBody(), responseType);
        }
    }

    private <T> String serialize(T result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("序列化幂等响应失败", e);
        }
    }

    private <T> T deserialize(String body, Class<T> responseType) {
        try {
            return objectMapper.readValue(body, responseType);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化幂等响应失败", e);
        }
    }
}
