package com.example.starter.baggage;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 幂等去重服务：所有写操作携带全局唯一 requestId。
 * 请求指纹为结构化规范化编码，包含非空操作者、操作、资源及全部参数（含期望版本）：
 * 对象按键名字典序排列，十进制数按数值归一（1 与 1.0 同指纹），文本原样区分大小写；
 * 同键同参重放原成功结果，同键异参返回 409；失败请求不占键，
 * 业务变更与去重记录在同一事务中原子提交，唯一键竞争时返回已提交的业务结果。
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    /** 单 JVM 内按 requestId 串行化，数据库唯一主键作为兜底。 */
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public IdempotencyService(JdbcTemplate jdbcTemplate,
                              TransactionTemplate transactionTemplate,
                              ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 在幂等保护下执行写操作。
     *
     * @param requestId     全局唯一请求标识
     * @param operator      非空操作者标识
     * @param operation     操作类型
     * @param resource      操作资源标识（航段或行李）
     * @param successStatus 成功响应的 HTTP 状态码（随记录保存，供审计）
     * @param payload       全部业务参数（不含 requestId，调用方需已规范化集合顺序）
     * @param resultType    成功结果类型，用于重放时反序列化
     * @param action        业务动作，成功结果与去重记录在同一事务提交
     * @return 已存储的成功结果（重放）或本次执行结果
     */
    public <T> T execute(String requestId, String operator, String operation, String resource,
                         int successStatus, Object payload, Class<T> resultType, Supplier<T> action) {
        String hash = fingerprint(operator, operation, resource, payload);
        Object lock = locks.computeIfAbsent(requestId, key -> new Object());
        synchronized (lock) {
            try {
                StoredRequest existing = find(requestId);
                if (existing != null) {
                    return replay(existing, hash, resultType);
                }
                try {
                    return transactionTemplate.execute(status -> {
                        T result = action.get();
                        jdbcTemplate.update(
                                "INSERT INTO request_log (request_id, operator, operation, resource,"
                                        + " request_hash, response_status, response_body)"
                                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                                requestId, operator, operation, resource,
                                hash, successStatus, writeJson(result));
                        return result;
                    });
                } catch (DuplicateKeyException duplicate) {
                    // 唯一主键兜底：其他线程/节点已写入同键记录，按已存储业务结果处理
                    StoredRequest stored = find(requestId);
                    if (stored == null) {
                        throw duplicate;
                    }
                    return replay(stored, hash, resultType);
                }
            } finally {
                locks.remove(requestId, lock);
            }
        }
    }

    /** 结构化防碰撞指纹：操作者/操作/资源/全参数统一规范化后取 SHA-256。 */
    public String fingerprint(String operator, String operation, String resource, Object payload) {
        Map<String, Object> envelope = new TreeMap<>();
        envelope.put("operator", operator);
        envelope.put("operation", operation);
        envelope.put("resource", resource);
        envelope.put("params", payload);
        String canonical = canonicalize(objectMapper.valueToTree(envelope), new StringBuilder()).toString();
        return sha256(canonical);
    }

    /**
     * 递归规范化 JSON：对象键排序、数组保序、数字按十进制数值归一、字符串按原文转义，
     * 结构本身携带类型与键名，不同类型/形状不会碰撞。
     */
    private static StringBuilder canonicalize(JsonNode node, StringBuilder out) {
        JsonNodeType type = node.getNodeType();
        switch (type) {
            case OBJECT -> {
                out.append('{');
                TreeMap<String, JsonNode> sorted = new TreeMap<>();
                node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
                boolean first = true;
                for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    appendJsonString(entry.getKey(), out).append(':');
                    canonicalize(entry.getValue(), out);
                }
                out.append('}');
            }
            case ARRAY -> {
                out.append('[');
                for (int i = 0; i < node.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    canonicalize(node.get(i), out);
                }
                out.append(']');
            }
            case NUMBER -> {
                BigDecimal value = new BigDecimal(node.asText()).stripTrailingZeros();
                out.append(value.signum() == 0 ? "0" : value.toPlainString());
            }
            case STRING -> appendJsonString(node.asText(), out);
            case BOOLEAN -> out.append(node.asBoolean());
            case NULL -> out.append("null");
            default -> throw new IllegalArgumentException("不支持的参数类型: " + type);
        }
        return out;
    }

    /** 最小 JSON 字符串转义，文本内容（含大小写、空白）原样参与指纹。 */
    private static StringBuilder appendJsonString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"');
    }

    private <T> T replay(StoredRequest stored, String hash, Class<T> resultType) {
        if (!stored.requestHash().equals(hash)) {
            throw ApiException.conflict("requestId 已被使用且请求参数不一致");
        }
        try {
            return objectMapper.readValue(stored.responseBody(), resultType);
        } catch (Exception ex) {
            throw new IllegalStateException("无法重放已存储的响应", ex);
        }
    }

    private StoredRequest find(String requestId) {
        List<StoredRequest> rows = jdbcTemplate.query(
                "SELECT request_hash, response_body FROM request_log WHERE request_id = ?",
                (rs, rowNum) -> new StoredRequest(rs.getString("request_hash"), rs.getString("response_body")),
                requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("请求参数无法序列化", ex);
        }
    }

    private static String sha256(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private record StoredRequest(String requestHash, String responseBody) {
    }
}
