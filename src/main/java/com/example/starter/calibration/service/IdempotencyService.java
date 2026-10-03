package com.example.starter.calibration.service;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等执行器：requestId 同参重放返回首次成功结果，异参 409，失败（异常或 null 结果）不占键。
 * 须在事务内调用：成功结果与幂等记录同事务提交，业务异常回滚即不占用 requestId。
 */
@Component
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 幂等执行。requestId 为 null 或空白时直接执行不记录。
     *
     * @param requestId   幂等请求 ID
     * @param fingerprint 请求参数指纹（同参重放必须一致）
     * @param type        结果类型（用于重放时反序列化）
     * @param action      业务动作；返回 null 表示业务失败（如 410 STALE），不占用 requestId
     * @param <T>         结果类型
     * @return 首次执行结果或重放的首次结果
     */
    public <T> T execute(String requestId, String fingerprint, Class<T> type, Supplier<T> action) {
        if (requestId == null || requestId.isBlank()) {
            return action.get();
        }
        String id = requestId.trim();
        var existing = repository.find(id);
        if (existing.isPresent()) {
            var record = existing.get();
            if (!record.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已以不同参数使用: " + id);
            }
            try {
                return objectMapper.readValue(record.responseBody(), type);
            } catch (Exception ex) {
                throw new IllegalStateException("幂等记录反序列化失败: " + id, ex);
            }
        }
        T result = action.get();
        if (result == null) {
            return null;
        }
        try {
            repository.insert(id, fingerprint, objectMapper.writeValueAsString(result),
                    Instant.now(clock));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("幂等记录序列化失败: " + id, ex);
        }
        return result;
    }
}