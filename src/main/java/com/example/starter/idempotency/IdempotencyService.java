package com.example.starter.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;

import com.example.starter.domain.RequestLog;
import com.example.starter.error.BusinessException;
import com.example.starter.repo.RequestLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 幂等执行器：requestId 同参重放返回首次成功响应，异参返回 409；
 * 与业务写库处于同一事务，失败回滚时不消耗请求键。
 */
@Service
public class IdempotencyService {

    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(RequestLogRepository requestLogRepository, ObjectMapper objectMapper, Clock clock) {
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在调用方事务内执行幂等操作。
     *
     * @param requestId 请求标识
     * @param operation 操作类型
     * @param payload 请求体（用于参数一致性比对）
     * @param successStatus 首次成功的 HTTP 状态码
     * @param action 业务动作，返回可序列化为 JSON 的结果
     * @return 幂等执行结果
     */
    public IdempotentOutcome execute(String requestId, String operation, Object payload, int successStatus,
            Supplier<Object> action) {
        String payloadHash = sha256(toJson(payload));
        var existing = requestLogRepository.findById(requestId);
        if (existing.isPresent()) {
            RequestLog log = existing.get();
            if (!log.operation().equals(operation) || !log.payloadHash().equals(payloadHash)) {
                throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "相同 requestId 已用于不同参数的请求");
            }
            return new IdempotentOutcome(log.responseBody(), log.httpStatus(), true);
        }
        Object result = action.get();
        String body = toJson(result);
        requestLogRepository.insert(requestId, operation, payloadHash, successStatus, body,
                Instant.now(clock));
        return new IdempotentOutcome(body, successStatus, false);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}