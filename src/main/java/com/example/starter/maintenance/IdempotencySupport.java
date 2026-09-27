package com.example.starter.maintenance;

import com.example.starter.observation.ApiException;
import com.example.starter.observation.RequestLogEntry;
import com.example.starter.observation.RequestLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 写操作幂等支撑：基于 request_log 的去重记录实现“同参重放首次结果、异参 409、失败不占键”。
 * 与现有观测合并服务共用同一张 request_log 表，语义保持一致：
 * 先在写事务内占位，业务成功后回填响应并同事务提交；业务失败回滚不占键。
 */
@Component
public class IdempotencySupport {

    private static final String SEPARATOR = "";

    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    public IdempotencySupport(RequestLogRepository requestLogRepository, ObjectMapper objectMapper) {
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参抛 409；无记录返回 null 继续执行。
     */
    public <T> Replay<T> checkReplay(String requestId, String fingerprint, Class<T> bodyType) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new Replay<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，读取已提交结果：同参返回重放结果，异参抛 409。
     */
    public <T> Replay<T> insertPlaceholder(String requestId, String fingerprint, String operation,
                                           Class<T> bodyType) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new Replay<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
        }
    }

    /**
     * 业务成功后回填去重记录响应，与业务变更同事务提交。
     */
    public void complete(String requestId, int status, Object body) {
        requestLogRepository.complete(requestId, status, writeBody(body));
    }

    /**
     * 按请求标识查询去重记录（供需要自定义重放重建的调用方使用）；不存在时返回空。
     */
    public Optional<RequestLogEntry> findEntry(String requestId) {
        return requestLogRepository.find(requestId);
    }

    /**
     * 计算操作与参数的 SHA-256 指纹，用于同键异参判定。
     */
    public String fingerprint(String operation, String... parts) {
        StringBuilder raw = new StringBuilder(operation);
        for (String part : parts) {
            raw.append(SEPARATOR).append(part == null ? "<null>" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 将请求对象序列化为规范化 JSON 原文，作为指纹输入（保证同参重放判定稳定）。
     */
    public String canonicalJson(Object request) {
        return writeBody(request);
    }

    private String writeBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize body", e);
        }
    }

    private <T> T readBody(String json, Class<T> bodyType) {
        try {
            return objectMapper.readValue(json, bodyType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 重放结果：HTTP 状态码与响应体。
     */
    public record Replay<T>(int status, T body) {
    }
}
