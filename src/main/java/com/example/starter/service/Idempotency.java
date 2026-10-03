package com.example.starter.service;

import com.example.starter.repo.RequestKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 幂等支撑：同键同参重放历史响应，同键异参返回 409；
 * 请求键与业务写入同事务，任一失败回滚即不消费请求键。
 */
@Component
public class Idempotency {

    private final RequestKeyRepository requestKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public Idempotency(RequestKeyRepository requestKeys, ObjectMapper objectMapper, Clock clock) {
        this.requestKeys = requestKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在调用方事务内执行幂等操作。
     *
     * @param requestId 幂等请求键
     * @param operation 操作类型
     * @param fingerprint 业务参数指纹
     * @param resultType 响应类型
     * @param action 业务动作
     * @return 业务结果或重放的历史响应
     */
    public <T> T run(String requestId, String operation, String fingerprint,
            Class<T> resultType, Supplier<T> action) {
        var existing = requestKeys.find(requestId);
        if (existing.isPresent()) {
            var entry = existing.get();
            if (!entry.operation().equals(operation) || !entry.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict("REQUEST_ID_CONFLICT",
                        "请求键已使用且参数不一致").operation(operation).build();
            }
            try {
                return objectMapper.readValue(entry.responseBody(), resultType);
            } catch (Exception e) {
                throw new IllegalStateException("幂等响应快照损坏: " + requestId, e);
            }
        }
        requestKeys.insert(requestId, operation, fingerprint, clock.instant());
        T result = action.get();
        try {
            requestKeys.updateResponse(requestId, objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应快照序列化失败: " + requestId, e);
        }
        return result;
    }
}