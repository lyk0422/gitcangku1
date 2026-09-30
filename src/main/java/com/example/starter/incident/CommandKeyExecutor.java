package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 命令幂等执行器：commandKey 全局唯一；同键同参重放首次成功响应快照，
 * 同键改参返回 409；业务失败随事务回滚，失败不占键。
 * 指纹由操作者、操作类型与规范化参数集合以长度前缀拼接后取 SHA-256，
 * 不依赖未转义分隔符，任意参数内容均无拼接歧义。
 */
@Component
public class CommandKeyExecutor {

    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CommandKeyExecutor(CommandKeyRepository commandKeys, ObjectMapper objectMapper, Clock clock) {
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 幂等执行：先占位（同事务内补写响应），并发同键由唯一约束串行化。
     */
    public <T> T run(String commandKey, String operation, String fingerprint,
                     Class<T> type, Supplier<T> business) {
        var existing = commandKeys.find(commandKey);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, fingerprint, type);
        }
        try {
            commandKeys.insertPlaceholder(commandKey, operation, fingerprint, clock.instant());
        } catch (DuplicateKeyException e) {
            var committed = commandKeys.findForUpdate(commandKey)
                    .orElseThrow(() -> ApiException.conflict("commandKey 处理冲突: " + commandKey));
            return replay(committed, operation, fingerprint, type);
        }
        T result = business.get();
        commandKeys.fillResponse(commandKey, 200, toJson(result));
        return result;
    }

    private <T> T replay(CommandKeyRecord record, String operation, String fingerprint, Class<T> type) {
        if (!record.operation().equals(operation) || !record.requestHash().equals(fingerprint)) {
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

    /**
     * 构造请求指纹：各参数以「长度:值;」长度前缀拼接（不依赖未转义分隔符）后取 SHA-256。
     */
    public static String fingerprint(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "" : part;
            sb.append(value.length()).append(':').append(value).append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
