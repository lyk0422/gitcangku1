package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.IdempotencyRecord;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等去重服务：以 requestId 为键，身份指纹为操作、操作者、资源、全部参数与期望版本的
 * 结构化 JSON（避免分隔符碰撞）。同键同参重放首次完整响应，异参 409，失败不占键；
 * 去重记录与业务写入在同一事务内原子提交，并发竞争不泄露唯一键异常。
 */
@Service
public class IdempotencyService {

    /** 请求键最大长度（与表结构一致）。 */
    static final int MAX_REQUEST_ID_LENGTH = 64;

    private final IdempotencyRepository idempotency;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository idempotency, ObjectMapper objectMapper) {
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    /**
     * 在事务内执行带幂等去重的写操作。requestId 为空时仅保证事务性，不做去重。
     *
     * @param requestId       幂等请求键（可空）
     * @param operation       操作类型
     * @param operator        操作者（可空）
     * @param resource        资源标识（可空）
     * @param params          全部请求参数（结构化对象，序列化后参与比较）
     * @param expectedVersion 期望版本（可空，仅参与身份比较）
     * @param business        业务逻辑
     * @param responseType    响应类型（用于重放反序列化）
     */
    @Transactional
    public <T> T execute(String requestId, String operation, String operator, String resource,
                         Object params, Long expectedVersion,
                         Supplier<T> business, Class<T> responseType) {
        if (requestId == null || requestId.isBlank()) {
            return business.get();
        }
        String rid = requestId.trim();
        if (rid.length() > MAX_REQUEST_ID_LENGTH) {
            throw ApiException.badRequest("requestId 长度不能超过 " + MAX_REQUEST_ID_LENGTH);
        }
        String fingerprint = fingerprint(operation, operator, resource, params, expectedVersion);

        var existing = idempotency.findById(rid);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, responseType);
        }
        try {
            idempotency.insertPending(rid, operation, fingerprint, Instant.now());
        } catch (DuplicateKeyException ex) {
            // 并发同键：首个请求已提交，读取其完整响应重放，不向调用方泄露唯一键异常
            IdempotencyRecord committed = idempotency.findById(rid)
                    .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_IN_PROGRESS",
                            "相同 requestId 的请求正在处理中，请稍后重试"));
            return replay(committed, fingerprint, responseType);
        }
        T result = business.get();
        idempotency.complete(rid, writeJson(result), Instant.now());
        return result;
    }

    /**
     * 重放首次响应：指纹不一致返回 409；记录尚未完成（并发处理中）返回 409。
     */
    private <T> T replay(IdempotencyRecord record, String fingerprint, Class<T> responseType) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同 requestId 但请求参数不一致");
        }
        if (record.responseBody() == null) {
            throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS", "相同 requestId 的请求正在处理中，请稍后重试");
        }
        try {
            return objectMapper.readValue(record.responseBody(), responseType);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应反序列化失败: " + record.requestId(), ex);
        }
    }

    /**
     * 构造身份指纹：固定字段顺序的结构化 JSON，键值类型由 JSON 表达，不存在分隔符碰撞。
     */
    private String fingerprint(String operation, String operator, String resource,
                               Object params, Long expectedVersion) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", operation);
        identity.put("operator", operator);
        identity.put("resource", resource);
        identity.put("params", params);
        identity.put("expectedVersion", expectedVersion);
        return writeJson(identity);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等身份序列化失败", ex);
        }
    }
}
