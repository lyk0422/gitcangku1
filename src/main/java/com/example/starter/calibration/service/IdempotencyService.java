package com.example.starter.calibration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.IdempotentReplayException;
import com.example.starter.calibration.model.IdempotencyRecord;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等请求裁决：requestId 唯一；指纹包含操作者、操作类型、规范化对象集合及所有影响结果的字段。
 * 字段编码采用长度前缀（length:value），集合不通过未转义分隔符拼接。
 * 同参重放返回首次完整响应快照；异参 409；失败请求随事务回滚不占键。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算请求指纹：操作类型、操作者与全部字段按长度前缀编码后取 SHA-256。
     */
    public String fingerprint(String operation, String actor, List<String> fields) {
        StringBuilder encoded = new StringBuilder();
        appendLengthPrefixed(encoded, operation);
        appendLengthPrefixed(encoded, actor);
        appendLengthPrefixed(encoded, String.valueOf(fields.size()));
        for (String field : fields) {
            appendLengthPrefixed(encoded, field);
        }
        return sha256Hex(encoded.toString());
    }

    /**
     * 在事务内执行幂等操作。须由 @Transactional 方法调用：
     * 占位记录与业务写入同事务提交；业务失败抛出异常时整体回滚，requestId 不被占用。
     *
     * @param requestId     幂等请求 ID
     * @param operation     操作类型
     * @param actor         操作者
     * @param fields        影响结果的全部规范化字段
     * @param successStatus 成功响应的 HTTP 状态码
     * @param action        业务动作，返回值为成功响应体（将被序列化为快照）
     */
    public <T> T execute(String requestId, String operation, String actor, List<String> fields,
                         int successStatus, Supplier<T> action) {
        String fingerprint = fingerprint(operation, actor, fields);
        var existing = repository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint, requestId);
        }
        try {
            repository.insertPlaceholder(requestId, fingerprint, operation, actor, Instant.now());
        } catch (DuplicateKeyException ex) {
            // 并发同 requestId 请求已提交：读取其快照裁决
            IdempotencyRecord committed = repository.findByRequestId(requestId)
                    .orElseThrow(() -> ex);
            return replayOrConflict(committed, fingerprint, requestId);
        }
        T result = action.get();
        repository.complete(requestId, successStatus, toJson(result));
        return result;
    }

    private <T> T replayOrConflict(IdempotencyRecord record, String fingerprint, String requestId) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已被不同参数的请求使用: " + requestId);
        }
        throw new IdempotentReplayException(record.responseStatus(), record.responseBody());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应快照序列化失败", ex);
        }
    }

    private static void appendLengthPrefixed(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
