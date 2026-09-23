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

import java.time.Clock;

/**
 * 幂等命令执行支持：requestId/commandKey 全局唯一，先占位插入、同事务补写首次成功响应。
 * 同键同参重放首次响应；同键异参 409；业务失败随事务回滚不占键。
 * 与 {@link IncidentService} 内既有约定一致，供提案等新写路径复用。
 */
@Component
public class IdempotencySupport {

    private static final String SEP = "\u001F";

    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencySupport(CommandKeyRepository commandKeys, ObjectMapper objectMapper, Clock clock) {
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在当前事务内幂等执行业务：占位键 → 业务 → 补写响应。
     * 占位唯一冲突时锁读已提交记录并重放；若对方仍在处理（响应为空）返回 409。
     */
    public <T> T run(String requestId, String operation, String requestHash, Class<T> type,
                     Supplier<T> business) {
        var existing = commandKeys.find(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, requestHash, type);
        }
        try {
            commandKeys.insertPlaceholder(requestId, operation, requestHash, clock.instant());
        } catch (DuplicateKeyException e) {
            var committed = commandKeys.findForUpdate(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId 处理冲突: " + requestId));
            return replay(committed, operation, requestHash, type);
        }
        T result = business.get();
        commandKeys.fillResponse(requestId, 200, toJson(result));
        return result;
    }

    private <T> T replay(CommandKeyRecord record, String operation, String requestHash, Class<T> type) {
        if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
            throw ApiException.conflict("requestId 已被不同参数的请求使用: " + record.commandKey());
        }
        if (record.responseBody() == null) {
            throw ApiException.conflict("requestId 正在处理中: " + record.commandKey());
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

    /** 计算规范化参数的 SHA-256 摘要（与既有命令键口径一致）。 */
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
