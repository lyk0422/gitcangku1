package com.example.starter.calibration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.IdempotencyRecord;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等服务：requestId 同参重放首次结果，异参 409，失败（非 2xx）不占键。
 * 幂等记录与业务写入同事务提交，保证并发下同键最多一个成功执行。
 */
@Service
public class IdempotencyService {

    /**
     * 已存储的响应：HTTP 状态码与 JSON 报文。
     *
     * @param status HTTP 状态码
     * @param body   JSON 报文
     */
    public record StoredResponse(int status, String body) {
    }

    private final IdempotencyRepository idempotency;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository idempotency, ObjectMapper objectMapper) {
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等执行。canonicalParams 为规范化参数（按声明序序列化），同 requestId 异参返回 409；
     * 仅 2xx 响应写入幂等记录，失败抛出异常时事务回滚、不占键。
     */
    @Transactional
    public StoredResponse execute(String endpoint, String requestId, Object canonicalParams,
                                  Supplier<StoredResponse> action) {
        String hash = sha256(serialize(canonicalParams));
        var existing = idempotency.find(endpoint, requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }
        StoredResponse response = action.get();
        if (response.status() < 400) {
            try {
                idempotency.insert(endpoint, requestId, hash, response.status(), response.body(),
                        Instant.now());
            } catch (DuplicateKeyException ex) {
                // 并发同键：另一事务已提交，重放其结果
                return replay(idempotency.find(endpoint, requestId).orElseThrow(), hash);
            }
        }
        return response;
    }

    /**
     * 将响应 DTO 序列化为 JSON 报文。
     */
    public String toJson(Object dto) {
        return serialize(dto);
    }

    private StoredResponse replay(IdempotencyRecord record, String hash) {
        if (!record.paramsHash().equals(hash)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已以不同参数执行: " + record.requestId());
        }
        return new StoredResponse(record.responseStatus(), record.responseBody());
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化失败", ex);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
