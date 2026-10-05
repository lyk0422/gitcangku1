package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.IdempotencyRecord;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等守卫：requestId 抢占、身份指纹比较与首次响应重放。
 * 身份指纹为操作、操作者、资源及全部参数（含期望版本）的规范化 JSON（键排序），
 * 结构化比较避免分隔符碰撞；记录与业务写入同事务提交，业务失败回滚即不占键。
 */
@Component
public class IdempotencyGuard {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyGuard(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算请求身份指纹：操作 + 操作者 + 资源 + 全部参数的规范化 JSON。
     */
    public String fingerprint(String operation, String actor, String resource, Map<String, Object> params) {
        Map<String, Object> identity = new TreeMap<>();
        identity.put("operation", operation);
        identity.put("actor", actor);
        identity.put("resource", resource);
        identity.put("params", new TreeMap<>(params));
        try {
            return objectMapper.writeValueAsString(identity);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等身份序列化失败", ex);
        }
    }

    /**
     * 抢占请求键：成功返回 true；键已存在返回 false（由调用方转入重放/冲突判定）。
     * 唯一键冲突在数据库层串行化，并发竞争不会向调用方泄露唯一键异常。
     */
    public boolean tryAcquire(String requestId, String fingerprint) {
        try {
            repository.insert(requestId, fingerprint, Instant.now());
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /**
     * 键已被占用时判定：指纹一致则重放首次完整响应；不一致返回 409；
     * 记录存在但响应未提交（并发处理中）返回 409 提示稍后重试。
     */
    public <T> T replay(String requestId, String fingerprint, Class<T> type) {
        IdempotencyRecord record = repository.findById(requestId)
                .orElseThrow(() -> ApiException.conflict("REQUEST_IN_PROGRESS",
                        "相同 requestId 的请求正在处理，请稍后重试"));
        if (!record.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同 requestId 的请求参数不一致");
        }
        if (record.responseBody() == null) {
            throw ApiException.conflict("REQUEST_IN_PROGRESS",
                    "相同 requestId 的请求正在处理，请稍后重试");
        }
        try {
            return objectMapper.readValue(record.responseBody(), type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应反序列化失败", ex);
        }
    }

    /**
     * 业务成功后写入首次完整响应（与业务写入同一事务提交）。
     */
    public void complete(String requestId, Object response) {
        try {
            repository.storeResponse(requestId, objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应序列化失败", ex);
        }
    }
}
