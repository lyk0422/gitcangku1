package com.example.starter.exposure.exposure;

import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * 写操作幂等执行器：业务变更与幂等记录在同一事务内原子提交。
 *
 * <p>以 requestId 为全局幂等键：同键同操作同参重放原成功响应；
 * 操作或参数指纹不同返回 409；业务失败随事务回滚，不占用幂等键。
 * 并发同键插入冲突时等待胜出事务提交后重放其结果。</p>
 */
@Component
public class IdempotentRunner {

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public IdempotentRunner(IdempotencyRepository idempotencyRepository,
                            ObjectMapper objectMapper,
                            TransactionTemplate txTemplate,
                            Clock clock) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
        this.clock = clock;
    }

    /**
     * 在事务内执行业务并维护幂等记录。
     *
     * @param requestId    全局幂等键
     * @param operation    操作类型标识（不同操作复用同键视为冲突）
     * @param fingerprint  请求参数指纹（不含 requestId）
     * @param responseType 成功响应类型，用于重放反序列化
     */
    public <T> T run(String requestId, String operation, String fingerprint,
                     Class<T> responseType, Supplier<T> action) {
        long deadline = System.currentTimeMillis() + IDEMPOTENT_WAIT_MILLIS;
        while (true) {
            try {
                return txTemplate.execute(status -> {
                    var existing = idempotencyRepository.lockById(requestId);
                    if (existing.isPresent()) {
                        IdempotencyRecord record = existing.get();
                        if (!record.operation().equals(operation)
                                || !record.requestFingerprint().equals(fingerprint)) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "idempotency key reused with different parameters: " + requestId);
                        }
                        try {
                            return objectMapper.readValue(record.responseJson(), responseType);
                        } catch (Exception e) {
                            throw new IllegalStateException("failed to replay idempotent response", e);
                        }
                    }
                    T result = action.get();
                    idempotencyRepository.insert(new IdempotencyRecord(
                            requestId, operation, fingerprint, writeJson(result)),
                            clock.millis());
                    return result;
                });
            } catch (DuplicateKeyException duplicate) {
                // 同键并发事务抢先插入（可能尚未提交），等待后重放
                if (System.currentTimeMillis() >= deadline) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "concurrent idempotency key conflict: " + requestId);
                }
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "interrupted");
                }
            }
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
