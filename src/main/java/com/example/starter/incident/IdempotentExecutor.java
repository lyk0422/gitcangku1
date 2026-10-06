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
 * 幂等执行器：requestId 全局唯一（落 command_keys 表），
 * 同键同参重放首次完整响应，同键改参 409；并发同键由唯一约束串行化，
 * 竞争不暴露为唯一键 500；业务失败同事务回滚，失败不占键；
 * 业务写入与去重记录同事务原子提交。
 */
@Component
public class IdempotentExecutor {

    private static final String SEP = "\\u001F";

    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotentExecutor(CommandKeyRepository commandKeys, ObjectMapper objectMapper, Clock clock) {
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 幂等执行：先占位插入 requestId，同事务内执行业务并补写首次响应；
     * 同键已存在时按操作与参数摘要比对，一致则重放首次响应，不一致返回 409。
     */
    public <T> T execute(String requestId, String operation, String requestHash,
                         Class<T> type, Supplier<T> business) {
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

    /**
     * 结构化参数摘要：操作、资源、全参数及期望版本按序拼接取 SHA-256；
     * 十进制参数由调用方按解析后的数值传入（按值比较），文本参数原样传入（不规范化）。
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
