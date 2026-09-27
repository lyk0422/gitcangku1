package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 命令幂等执行器：commandKey 全局唯一，同键同参重放首次完整成功响应快照，
 * 同键改参返回 409，业务失败事务回滚不占键；并发同键由唯一约束串行化。
 * 指纹（requestHash）覆盖操作者、操作类型、规范化集合和全部结果相关字段；
 * 集合元素先转义再排序连接，不直接以未转义分隔符拼接。
 */
@Component
public class CommandKeyExecutor {

    /** 指纹字段分隔符（UNIT SEPARATOR），不出现在规范化后的元素内。 */
    private static final String SEP = "";

    /** 转义前缀字符。 */
    private static final char ESCAPE = '\\';

    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CommandKeyExecutor(CommandKeyRepository commandKeys, ObjectMapper objectMapper,
                              Clock clock) {
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 幂等执行：同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化。
     * 业务校验或写入失败抛异常时事务整体回滚，键不被占用。
     */
    public <T> T run(String commandKey, String operation, String requestHash,
                     Class<T> type, Supplier<T> business) {
        var existing = commandKeys.find(commandKey);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, requestHash, type);
        }
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

    /**
     * 序列化首次成功响应快照。
     */
    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    /**
     * 标量字段指纹：各字段以 UNIT SEPARATOR 连接后取 SHA-256。
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

    /**
     * 规范化集合：去空白、去重、排序；元素内的转义符与分隔符先转义，
     * 保证不同集合不会规范化出相同指纹输入。
     */
    public static List<String> normalizeSet(Collection<String> values) {
        return values.stream().map(String::strip).distinct().sorted().toList();
    }

    /**
     * 集合指纹：规范化后逐元素转义（\ → \\，分隔符 → \ 分隔符）再以分隔符连接取 SHA-256，
     * 集合不直接以未转义分隔符拼接。
     */
    public static String hashSet(Collection<String> values) {
        List<String> normalized = normalizeSet(values);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < normalized.size(); i++) {
            if (i > 0) {
                joined.append(SEP);
            }
            joined.append(escape(normalized.get(i)));
        }
        return hash(joined.toString());
    }

    /**
     * 元素转义：先转义转义符自身，再转义分隔符，保证连接无歧义。
     */
    public static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ESCAPE || c == SEP.charAt(0)) {
                out.append(ESCAPE);
            }
            out.append(c);
        }
        return out.toString();
    }
}
