package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 命令幂等执行器：同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化。
 * 业务异常不捕获，随外层事务回滚，占位行一并回滚，失败不占用幂等键。
 */
@Component
public class Idempotency {

    private static final String SEP = "";

    private final ObjectMapper objectMapper;

    public Idempotency(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 在已锁定聚合根的事务内执行幂等业务。
     */
    public <T> T run(CommandKeyStore store, String commandKey, String operation, String requestHash,
                     Class<T> type, java.time.Instant now, Supplier<T> business) {
        var existing = store.find(commandKey);
        if (existing.isPresent()) {
            return replay(store, existing.get(), operation, requestHash, type);
        }
        try {
            store.insertPlaceholder(commandKey, operation, requestHash, now);
        } catch (DuplicateKeyException e) {
            var committed = store.findForUpdate(commandKey)
                    .orElseThrow(() -> ApiException.conflict("commandKey 处理冲突: " + commandKey));
            return replay(store, committed, operation, requestHash, type);
        }
        T result = business.get();
        store.fillResponse(commandKey, 200, toJson(result));
        return result;
    }

    private <T> T replay(CommandKeyStore store, CommandKeyRecord record, String operation,
                         String requestHash, Class<T> type) {
        if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
            throw ApiException.conflict("commandKey 已被不同参数的请求使用: " + record.commandKey());
        }
        if (record.responseBody() == null) {
            throw ApiException.conflict("commandKey 正在处理中: " + record.commandKey());
        }
        try {
            return objectMapper.readValue(record.responseBody(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    /**
     * 计算规范化请求参数的 SHA-256 摘要（单元分隔符拼接）。
     */
    public static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(String.join(SEP, parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
