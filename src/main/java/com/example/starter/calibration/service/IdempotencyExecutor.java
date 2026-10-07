package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.repo.IdempotencyRepository;

/**
 * 幂等写执行器：全局 requestId 去重，身份指纹区分同键异参。
 * 去重记录与业务写入在同一事务原子提交：业务失败整体回滚，失败可复用键；
 * 唯一键竞争时以先提交者的业务结果为准。
 */
@Component
public class IdempotencyExecutor {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyExecutor(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 在调用方事务内执行幂等写。requestId 非空且不超过 64 字符，否则 400。
     * 同 requestId 同指纹返回首次完整响应；同 requestId 异指纹返回 409。
     *
     * @param requestId   全局请求 ID
     * @param operation   操作类型
     * @param fingerprint 身份指纹（见 {@link Fingerprints}）
     * @param type        响应类型
     * @param business    业务动作，仅在首次执行时运行
     */
    public <T> T execute(String requestId, String operation, String fingerprint,
                         Class<T> type, Supplier<T> business) {
        String id = Inputs.requireText(requestId, "requestId");
        if (id.length() > 64) {
            throw ApiException.badRequest("requestId 长度不能超过 64 字符");
        }
        var existing = repository.find(id);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, type);
        }
        try {
            repository.insert(id, operation, fingerprint, null, Instant.now());
        } catch (DuplicateKeyException ex) {
            // 并发同键：先提交者已给出业务结果，直接重放
            return replay(repository.find(id).orElseThrow(() ->
                    ApiException.conflict("IDEMPOTENCY_CONFLICT", "请求 ID 冲突: " + id)), fingerprint, type);
        }
        T result = business.get();
        repository.updateResponse(id, writeJson(result));
        return result;
    }

    private <T> T replay(IdempotencyRepository.IdempotencyRecord record, String fingerprint, Class<T> type) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "请求 ID 已用于不同参数的请求: " + record.requestId());
        }
        try {
            return objectMapper.readValue(record.responseBody(), type);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等响应反序列化失败", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等响应序列化失败", ex);
        }
    }
}
