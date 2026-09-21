package com.example.starter.curtailment.idempotency;

import com.example.starter.curtailment.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * 幂等执行器：同事务内先占位命令记录，业务成功后回写首次响应。
 * 同键同参重放返回首次结果；同键改参返回 409；业务失败整体回滚，命令键可再次使用。
 */
@Component
public class IdempotencyExecutor {

    private final IdempotentCommandRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyExecutor(IdempotentCommandRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 在调用方事务内执行幂等命令。
     *
     * @param commandKey  命令幂等键
     * @param operation   操作类型
     * @param fingerprint 请求参数指纹
     * @param resultType  结果类型，用于重放反序列化
     * @param action      业务动作，仅首次执行
     */
    public <T> T execute(String commandKey, String operation, String fingerprint, Class<T> resultType,
                         Supplier<T> action) {
        var existing = repository.findByKey(commandKey);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, fingerprint, resultType);
        }
        try {
            repository.insert(commandKey, operation, fingerprint, Instant.now());
        } catch (DuplicateKeyException ex) {
            // 并发同键：等待对方事务提交后读取首次结果。
            StoredCommand stored = repository.findByKeyForUpdate(commandKey)
                    .orElseThrow(() -> ApiException.conflict("命令记录状态异常"));
            return replay(stored, operation, fingerprint, resultType);
        }
        T result = action.get();
        repository.complete(commandKey, 200, writeJson(result));
        return result;
    }

    private <T> T replay(StoredCommand stored, String operation, String fingerprint, Class<T> resultType) {
        if (!stored.operation().equals(operation) || !stored.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("commandKey 已被不同参数的命令占用");
        }
        if (stored.responseBody() == null) {
            throw ApiException.conflict("相同命令正在处理中，请稍后重试");
        }
        try {
            return objectMapper.readValue(stored.responseBody(), resultType);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等响应反序列化失败", ex);
        }
    }

    private String writeJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等响应序列化失败", ex);
        }
    }
}
