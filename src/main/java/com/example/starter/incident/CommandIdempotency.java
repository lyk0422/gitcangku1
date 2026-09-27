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
 * 命令幂等执行组件：commandKey 全局唯一，同键同参重放首次响应，同键改参 409；
 * 并发同键由唯一约束串行化；业务失败事务回滚不占键。
 * preCheck 钩子在同键重放判定之后、占位插入之前执行，用于需要先固化证据
 * （如授权到期判定）再决定是否占键的场景。
 */
@Component
public class CommandIdempotency {

    private static final String SEP = "\\u001F";

    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CommandIdempotency(CommandKeyRepository commandKeys, ObjectMapper objectMapper,
                              Clock clock) {
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 幂等执行（无前置检查）。
     */
    public <T> T run(String commandKey, String operation, String requestHash,
                     Class<T> type, Supplier<T> business) {
        return run(commandKey, operation, requestHash, type, () -> { }, business);
    }

    /**
     * 幂等执行：先按键重放判定；preCheck 在占位插入前执行，其抛出的异常不会占键
     * （若调用方配置 dontRollbackOn，preCheck 内固化的证据随事务提交）。
     */
    public <T> T run(String commandKey, String operation, String requestHash, Class<T> type,
                     Runnable preCheck, Supplier<T> business) {
        var existing = commandKeys.find(commandKey);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, requestHash, type);
        }
        preCheck.run();
        try {
            commandKeys.insertPlaceholder(commandKey, operation, requestHash, clock.instant());
        } catch (DuplicateKeyException e) {
            var committed = commandKeys.findForUpdate(commandKey)
                    .orElseThrow(() -> ApiException.conflict("commandKey 处理冲突: " + commandKey));
            return replay(committed, operation, requestHash, type);
        }
        T result = business.get();
        commandKeys.fillResponse(commandKey, 200, toJson(result));
        return result;
    }

    private <T> T replay(CommandKeyRecord record, String operation, String requestHash,
                         Class<T> type) {
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
     * 规范化请求参数的 SHA-256 摘要，用于同键改参检测。
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
