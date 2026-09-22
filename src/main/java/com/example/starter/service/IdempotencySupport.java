package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.example.starter.repository.IdempotencyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 写操作幂等支撑：requestId 全局唯一；同键同参重放原成功结果，同键异参返回 409。
 * 幂等记录与业务变更在同一事务内原子提交；失败不占键。
 */
@Component
public class IdempotencySupport {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencySupport(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算规范化请求参数哈希（JSON 紧凑序列化后 SHA-256）。
     */
    public String hash(Object request) {
        try {
            String canonical = objectMapper.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("failed to hash idempotent request", e);
        }
    }

    /**
     * 查找已提交的成功记录；存在但参数不同抛 409。
     */
    public IdempotencyRepository.Record requireMatch(String requestId, String operation, String requestHash) {
        return repository.findByRequestId(requestId).map(record -> {
            if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
                throw new ConflictException(
                        "requestId '" + requestId + "' was already used with different parameters");
            }
            return record;
        }).orElse(null);
    }

    /**
     * 提交成功记录；并发同键插入落败方重取记录（同参重放、异参 409）。
     */
    public void commit(String requestId, String operation, String requestHash,
                       int httpStatus, Object response, long createdAt) {
        String body = writeJson(response);
        try {
            repository.insert(requestId, operation, requestHash, httpStatus, body, createdAt);
        } catch (DuplicateKeyException e) {
            IdempotencyRepository.Record existing = repository.findByRequestId(requestId)
                    .orElseThrow(() -> new IllegalStateException("idempotency record vanished: " + requestId));
            if (!existing.operation().equals(operation) || !existing.requestHash().equals(requestHash)) {
                throw new ConflictException(
                        "requestId '" + requestId + "' was already used with different parameters");
            }
            // 同键同参的并发提交：视为重放，业务事务随后回滚并由外层重放原结果。
            throw new IdempotentReplayException(existing);
        }
    }

    /**
     * 将响应对象序列化为 JSON 字符串存储，重放时原样回传。
     */
    public String writeJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize idempotent response", e);
        }
    }

    /**
     * 并发同键同参提交时的内部信号：事务回滚后外层重放原结果。
     */
    public static class IdempotentReplayException extends RuntimeException {

        private final transient IdempotencyRepository.Record record;

        public IdempotentReplayException(IdempotencyRepository.Record record) {
            super("concurrent idempotent replay for requestId " + record.requestId(), null,
                    false, false);
            this.record = record;
        }

        public IdempotencyRepository.Record getRecord() {
            return record;
        }
    }
}
