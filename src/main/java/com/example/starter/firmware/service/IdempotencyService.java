package com.example.starter.firmware.service;

import com.example.starter.firmware.domain.DigestRules;
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
        return execute(requestId, api, fingerprint, action, type, result -> true);
    }

    /**
     * 与 {@link #execute} 相同，但可通过 storeResult 判定业务结果是否占用 requestId。
     * 返回 false 表示该结果属于业务失败（如完整性失败）：事务照提交，但不写幂等记录（失败不占键），
     * 之后同 requestId 可再次使用。
     */
    public <T> T execute(String requestId, String api, String fingerprint, Supplier<T> action, Class<T> type,
                         java.util.function.Predicate<T> storeResult) {
        synchronized (locks[Math.floorMod(requestId.hashCode(), LOCK_STRIPES)]) {
            try {
                return transactionTemplate.execute(status ->
                        executeInTransaction(requestId, api, fingerprint, action, type, storeResult));
            } catch (ReplayNeededException e) {
                return replayCommitted(requestId, fingerprint, type);
            }
        }
    }

    private <T> T executeInTransaction(String requestId, String api, String rawFingerprint,
                                       Supplier<T> action, Class<T> type,
                                       java.util.function.Predicate<T> storeResult) {
        // 指纹统一哈希为64位十六进制，避免多分片等长参数超出列长；同参仍稳定相等、异参必不冲突
        String fingerprint = DigestRules.sha256Hex(api + "|" + rawFingerprint);
        var existing = repository.find(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, type);
        }
        T result = action.get();
        if (!storeResult.test(result)) {
            return result;
        }
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
