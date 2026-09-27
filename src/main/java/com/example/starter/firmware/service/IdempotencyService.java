package com.example.starter.firmware.service;

import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 写操作幂等执行器：同一事务内完成业务变更与去重记录写入，原子提交。
 * 同键同参重放原成功结果；同键异参返回 409；业务失败回滚，不占键。
 * 同 requestId 的执行通过分段锁串行化，避免并发下检查与写入之间的竞态；
 * 数据库唯一约束作为最终兜底。
 */
@Service
public class IdempotencyService {

    /**
     * 内部信号：并发下同键插入冲突，回滚当前事务后改为重放已提交记录。
     */
    private static final class ReplayNeededException extends RuntimeException {
    }

    private static final int LOCK_STRIPES = 256;

    private final Object[] locks = new Object[LOCK_STRIPES];

    private final IdempotencyRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository repository, TransactionTemplate transactionTemplate,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    public <T> T execute(String requestId, String api, String fingerprint, Supplier<T> action, Class<T> type) {
        synchronized (locks[Math.floorMod(requestId.hashCode(), LOCK_STRIPES)]) {
            try {
                return transactionTemplate.execute(status -> executeInTransaction(requestId, api, fingerprint, action, type));
            } catch (ReplayNeededException e) {
                return replayCommitted(requestId, fingerprint, type);
            }
        }
    }

    /**
     * 已裁决写操作：业务动作可以返回“已提交的失败”（如分片完整性失败，证据必须落库固化）。
     * 成功结果正常写入幂等记录；已提交失败不占键（同 requestId 重试会重新执行业务逻辑，
     * 业务侧须保证重放确定且不产生重复证据），事务提交后再向调用方抛出该错误。
     */
    public <T> T executeAdjudicated(String requestId, String api, String fingerprint,
                                    Supplier<AdjudicatedOutcome<T>> action, Class<T> type) {
        synchronized (locks[Math.floorMod(requestId.hashCode(), LOCK_STRIPES)]) {
            AdjudicatedOutcome<T> outcome;
            try {
                outcome = transactionTemplate.execute(status -> {
                    var existing = repository.find(requestId);
                    if (existing.isPresent()) {
                        return AdjudicatedOutcome.ok(replay(existing.get(), fingerprint, type));
                    }
                    AdjudicatedOutcome<T> result = action.get();
                    if (result.error() == null) {
                        try {
                            repository.insert(requestId, api, fingerprint, writeJson(result.value()));
                        } catch (DuplicateKeyException e) {
                            throw new ReplayNeededException();
                        }
                    }
                    // error != null：不写幂等记录，事务正常提交以固化裁决证据。
                    return result;
                });
            } catch (ReplayNeededException e) {
                return replayCommitted(requestId, fingerprint, type);
            }
            if (outcome == null) {
                throw new IllegalStateException("裁决结果缺失");
            }
            if (outcome.error() != null) {
                throw outcome.error();
            }
            return outcome.value();
        }
    }

    /**
     * 动作裁决结果：value 为成功结果；error 为已提交事务后需抛给调用方的业务错误（不占幂等键）。
     */
    public record AdjudicatedOutcome<T>(T value, ApiException error) {
        public static <T> AdjudicatedOutcome<T> ok(T value) {
            return new AdjudicatedOutcome<>(value, null);
        }

        public static <T> AdjudicatedOutcome<T> committedFailure(ApiException error) {
            return new AdjudicatedOutcome<>(null, error);
        }
    }

    private <T> T executeInTransaction(String requestId, String api, String fingerprint,
                                       Supplier<T> action, Class<T> type) {
        var existing = repository.find(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, type);
        }
        T result = action.get();
        try {
            repository.insert(requestId, api, fingerprint, writeJson(result));
        } catch (DuplicateKeyException e) {
            throw new ReplayNeededException();
        }
        return result;
    }

    private <T> T replayCommitted(String requestId, String fingerprint, Class<T> type) {
        var record = repository.find(requestId)
                .orElseThrow(() -> new IllegalStateException("幂等记录缺失: " + requestId));
        return replay(record, fingerprint, type);
    }

    private <T> T replay(IdempotencyRepository.IdempotencyRecord record, String fingerprint, Class<T> type) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT", "requestId 已被不同参数的请求占用");
        }
        try {
            return objectMapper.readValue(record.responseBody(), type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }
}
