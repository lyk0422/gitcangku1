package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.function.Supplier;

import com.example.starter.repo.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 幂等执行器：同一 requestId 同参重放返回首次结果，异参重放抛 409。
 * 记录与业务写入在同一事务内提交；业务失败回滚即不消费请求键。
 */
@Component
public class IdempotencyExecutor {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyExecutor(IdempotencyRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public <T> T execute(String operation, String requestId, Object payload,
            Class<T> responseType, Supplier<T> action) {
        String hash = hash(payload);
        var existing = repository.find(operation, requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, responseType, operation, requestId);
        }
        T result = action.get();
        try {
            repository.insert(operation, requestId, hash, writeJson(result), java.time.Instant.now(clock));
        } catch (DuplicateKeyException e) {
            // 并发下同键先到者获胜：读取已提交结果按重放规则判定
            var winner = repository.find(operation, requestId);
            if (winner.isPresent()) {
                return replay(winner.get(), hash, responseType, operation, requestId);
            }
            throw e;
        }
        return result;
    }

    private <T> T replay(IdempotencyRepository.IdemRow row, String hash,
            Class<T> responseType, String operation, String requestId) {
        if (!row.payloadHash().equals(hash)) {
            throw BusinessException.conflict("IDEMPOTENCY_MISMATCH",
                    "requestId 已使用且参数不一致: " + requestId, null, operation);
        }
        try {
            return objectMapper.readValue(row.responseBody(), responseType);
        } catch (Exception e) {
            throw new IllegalStateException("幂等快照反序列化失败", e);
        }
    }

    private String hash(Object payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = writeJson(payload).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }
}