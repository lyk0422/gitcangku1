package com.example.starter.translation.service;

import com.example.starter.translation.error.ApiException;
import com.example.starter.translation.repo.RequestLogRepository;
import com.example.starter.translation.repo.RequestLogRepository.RequestLogRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 写操作幂等去重：requestId 全局唯一。
 * 同键同参重放原成功结果；同键异参返回 409；失败请求不占键；
 * 业务变更与去重记录在同一事务中原子提交。
 */
@Service
public class IdempotencyService {

    private final RequestLogRepository requestLogs;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(RequestLogRepository requestLogs, TransactionTemplate transactionTemplate,
                              ObjectMapper objectMapper, Clock clock) {
        this.requestLogs = requestLogs;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 已存储的成功响应。
     *
     * @param status HTTP 状态码
     * @param body   响应正文（JSON）
     */
    public record StoredResponse(int status, String body) {
    }

    /**
     * 请求指纹：方法、路径、操作者与请求体共同参与摘要，避免同键跨接口/跨操作者误放。
     *
     * @param method  HTTP 方法
     * @param path    请求路径
     * @param actor   操作者（X-Actor-Id）
     * @param payload 请求体对象
     */
    public record Fingerprint(String method, String path, String actor, Object payload) {
    }

    /**
     * 在单事务内执行业务并记录去重结果；重放时直接返回已存储结果。
     */
    public StoredResponse execute(String requestId, Fingerprint fingerprint, Supplier<Object> business) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("requestId 不能为空");
        }
        String hash = hash(fingerprint);
        var existing = requestLogs.find(requestId);
        if (existing.isPresent()) {
            RequestLogRow row = existing.get();
            if (row.requestHash().equals(hash)) {
                return new StoredResponse(row.status(), row.response());
            }
            throw ApiException.requestConflict("requestId 已被不同参数的请求占用: " + requestId);
        }
        try {
            return transactionTemplate.execute(tx -> {
                Object result = business.get();
                String body = toJson(result);
                requestLogs.insert(requestId, hash, 200, body, Instant.now(clock));
                return new StoredResponse(200, body);
            });
        } catch (DuplicateKeyException | ApiException e) {
            // 并发同 requestId：本事务已回滚（业务变更随之撤销）。
            // 若并发请求已提交同参成功结果，按"同键同参重放原成功结果"返回；
            // 若对方为异参则 409；若无任何已提交结果则抛出本请求自身的失败。
            var row = requestLogs.find(requestId);
            if (row.isPresent()) {
                if (row.get().requestHash().equals(hash)) {
                    return new StoredResponse(row.get().status(), row.get().response());
                }
                throw ApiException.requestConflict("requestId 已被不同参数的请求占用: " + requestId);
            }
            if (e instanceof ApiException apiException) {
                throw apiException;
            }
            throw ApiException.requestConflict("requestId 冲突: " + requestId);
        }
    }

    private String hash(Fingerprint fingerprint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(toJson(fingerprint).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
