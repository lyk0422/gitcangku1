package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 幂等编排（非事务）：计算请求指纹、处理并发下 command_key 唯一约束冲突。
 * 同键同参重放返回首次结果；同键改参返回 409；并发重复插入时回滚后重放先提交事务的结果。
 */
@Component
public class IdempotencyAdvisor {

    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyAdvisor(CommandLogRepository commandLogRepository, ObjectMapper objectMapper) {
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算请求指纹：先将操作人、操作类型、资源键与全部请求参数结构化为无碰撞字节序列，
     * 再做 SHA-256。十进制数值按 {@link BigDecimal#stripTrailingZeros()} 同值同参，
     * 文本原样保留，Base64 字节字段按解码字节参与比较。
     */
    public String hash(String operation, String actorId, String evidenceKey, Object request) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeField(out, "operation", operation == null ? "" : operation);
            writeField(out, "actorId", actorId == null ? "" : actorId);
            writeField(out, "resource", evidenceKey == null ? "" : evidenceKey);
            appendValue(out, "params", objectMapper.convertValue(request, Object.class));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(out.toByteArray()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalStateException("请求序列化失败", e);
        }
    }

    /**
     * 写字段：名字 UTF-8 字节数（4 字节大端）+ 名字 + 值字节数（4 字节大端）+ 值。
     * 长度前缀保证不同字段名/字段值的拼接不会发生碰撞。
     */
    private static void writeField(ByteArrayOutputStream out, String name, String value) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        writeInt(out, nameBytes.length);
        out.writeBytes(nameBytes);
        writeInt(out, valueBytes.length);
        out.writeBytes(valueBytes);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /**
     * 递归规范化参数值：
     * 标量带类型标记（s 文本原样 / n 十进制规范化 / b 布尔 / z null）；
     * 数组以元素个数与逐元素编码表示；对象字段名排序后按 TLV 输出，字段无碰撞。
     */
    private void appendValue(ByteArrayOutputStream out, String name, Object value) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        writeInt(out, nameBytes.length);
        out.writeBytes(nameBytes);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        encodeValue(body, value);
        writeInt(out, body.size());
        out.writeBytes(body.toByteArray());
    }

    private void encodeValue(ByteArrayOutputStream out, Object value) {
        if (value == null) {
            out.write('z');
        } else if (value instanceof Boolean b) {
            out.write('b');
            out.writeBytes(b ? "1".getBytes(StandardCharsets.UTF_8) : "0".getBytes(StandardCharsets.UTF_8));
        } else if (value instanceof Number n) {
            // 整数与浮点统一走十进制：1 与 1.0 规范化后相同；非有限浮点不参与业务请求。
            out.write('n');
            String normalized = new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
            out.writeBytes(normalized.getBytes(StandardCharsets.UTF_8));
        } else if (value instanceof String s) {
            out.write('s');
            out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        } else if (value instanceof Map<?, ?> map) {
            out.write('m');
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            writeInt(out, sorted.size());
            sorted.forEach((k, v) -> appendValue(out, k, v));
        } else if (value instanceof Iterable<?> iterable) {
            out.write('l');
            java.util.List<Object> elements = new java.util.ArrayList<>();
            iterable.forEach(elements::add);
            writeInt(out, elements.size());
            for (Object element : elements) {
                ByteArrayOutputStream item = new ByteArrayOutputStream();
                encodeValue(item, element);
                writeInt(out, item.size());
                out.writeBytes(item.toByteArray());
            }
        } else {
            // 未知 Jackson 节点类型按文本兜底，保证可确定性编码。
            out.write('s');
            out.writeBytes(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 在事务边界外执行命令；捕获并发导致的 command_key 唯一冲突并重放/报 409。
     */
    public StoredResponse guard(String commandKey, String requestHash,
                                Supplier<StoredResponse> transactionalCall) {
        try {
            return transactionalCall.get();
        } catch (DuplicateKeyException e) {
            Optional<CommandLogRepository.CommandLog> existing = commandLogRepository.findByKey(commandKey);
            if (existing.isPresent()) {
                CommandLogRepository.CommandLog log = existing.get();
                if (log.requestHash().equals(requestHash)) {
                    return new StoredResponse(log.responseStatus(), log.responseBody());
                }
                throw ApiException.conflict("幂等键已被不同参数使用: " + commandKey);
            }
            throw ApiException.conflict("资源键冲突，目标已存在");
        }
    }
}
