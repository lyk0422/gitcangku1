package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
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
     * 计算请求指纹：操作类型 + 操作人 + 资源键 + 请求体规范化 JSON 的 SHA-256。
     * 规范化规则：十进制按数值比较（1.0 与 1.00 同参），文本原样，对象字段按名排序。
     */
    public String hash(String operation, String actorId, String evidenceKey, Object request) {
        try {
            String canonical = String.join("\n",
                    operation,
                    actorId == null ? "" : actorId,
                    evidenceKey == null ? "" : evidenceKey,
                    canonicalJson(request));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalStateException("请求序列化失败", e);
        }
    }

    /**
     * 请求体规范化 JSON：十进制去尾零按数值输出，文本原样，对象字段按名排序，数组保持顺序。
     */
    private String canonicalJson(Object request) {
        StringBuilder out = new StringBuilder();
        writeCanonical(objectMapper.valueToTree(request), out);
        return out.toString();
    }

    private void writeCanonical(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) {
            out.append("null");
        } else if (node.isObject()) {
            out.append('{');
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                if (out.charAt(out.length() - 1) != '{') {
                    out.append(',');
                }
                out.append('"').append(name).append("\":");
                writeCanonical(node.get(name), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeCanonical(node.get(i), out);
            }
            out.append(']');
        } else if (node.isNumber()) {
            BigDecimal value = node.decimalValue();
            if (value.signum() == 0) {
                value = BigDecimal.ZERO;
            } else {
                value = value.stripTrailingZeros();
            }
            out.append(value.toPlainString());
        } else if (node.isTextual()) {
            // 文本原样，仅转义引号与反斜杠保证边界无歧义。
            out.append('"');
            String text = node.asText();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"' || c == '\\') {
                    out.append('\\');
                }
                out.append(c);
            }
            out.append('"');
        } else {
            out.append(node.asText());
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
