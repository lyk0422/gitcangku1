package com.example.starter.observation;

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
 * 质量标记写操作的幂等支撑：占位去重、同键同参重放、异参 409、成功响应回填与指纹计算。
 * 复用 request_log 表；业务失败随事务回滚，失败不占键。
 */
@Component
class FlagIdempotency {

    private static final String SEPARATOR = "";

    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    FlagIdempotency(RequestLogRepository requestLogRepository, ObjectMapper objectMapper) {
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等写操作结果：HTTP 状态码与已反序列化的首次成功响应体。
     */
    record Outcome(int status, Object body) {
    }

    /**
     * 同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    Outcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new Outcome(entry.responseStatus(), readBody(entry.operation(), entry.responseBody()));
    }

    /**
     * 占位写入去重记录；并发同键主键冲突时等待对方事务结束后读取：同参重放、异参 409。
     * 业务失败时占位随事务回滚，不占用该键。
     *
     * @return 重放结果；正常占位成功返回 null
     */
    Outcome insertPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new Outcome(entry.responseStatus(), readBody(entry.operation(), entry.responseBody()));
        }
    }

    /**
     * 业务成功后回填响应（与业务变更同事务提交），返回本次写操作结果。
     */
    Outcome complete(String requestId, int status, Object body) {
        requestLogRepository.complete(requestId, status, writeJson(body));
        return new Outcome(status, body);
    }

    /**
     * 计算操作与参数的 SHA-256 指纹。
     */
    String fingerprint(String operation, String... parts) {
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

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private Object readBody(String operation, String json) {
        try {
            Class<?> type = "FLAG_REVIEW".equals(operation)
                    ? QualityFlagReviewResponse.class : QualityFlagResponse.class;
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }
}
