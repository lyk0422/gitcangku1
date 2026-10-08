package com.example.starter.baggage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 幂等去重服务：所有写操作携带全局唯一 requestId。
 * 同键同参重放原成功结果，同键异参返回 409；失败请求不占键，
 * 业务变更与去重记录在同一事务中原子提交。
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
     * 在幂等保护下执行写操作（不记录操作者，兼容旧写入）。
     */
    public <T> T execute(String requestId, String operation, int successStatus,
                         Object payload, Class<T> resultType, Supplier<T> action) {
        return execute(requestId, operation, null, successStatus, payload, resultType, action);
    }

    /**
     * 在幂等保护下执行写操作。
     *
     * @param requestId     全局唯一请求标识
     * @param operation     操作类型
     * @param operator      操作者标识（随记录保存供审计，可为 null）
     * @param successStatus 成功响应的 HTTP 状态码（随记录保存，供审计）
     * @param payload       请求参数（不含 requestId，调用方需已规范化），与操作类型一起做结构化摘要
     * @param resultType    成功结果类型，用于重放时反序列化
     * @param action        业务动作，成功结果与去重记录在同一事务提交
     * @return 已存储的成功结果（重放）或本次执行结果
     */
    public <T> T execute(String requestId, String operation, String operator, int successStatus,
                         Object payload, Class<T> resultType, Supplier<T> action) {
        String hash = structuredHash(operation, payload);
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
                                "INSERT INTO request_log (request_id, operation, operator, request_hash,"
                                        + " response_status, response_body) VALUES (?, ?, ?, ?, ?, ?)",
                                requestId, operation, operator, hash, successStatus, writeJson(result));
                        return result;
                    });
                } catch (DuplicateKeyException duplicate) {
                    // 唯一主键兜底：其他节点已写入同键记录，按已存储结果处理
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

    /**
     * 结构化摘要：操作类型与全参数（含资源标识、期望版本）一起参与；
     * 对象字段按名排序，数组保序，十进制按值（去尾零）比较，文本原样比较。
     */
    private String structuredHash(String operation, Object payload) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("operation=").append(operation).append('\n');
        appendCanonical(objectMapper.valueToTree(payload), canonical);
        return sha256(canonical.toString());
    }

    private void appendCanonical(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) {
            out.append("null");
        } else if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            out.append('{');
            for (String name : names) {
                out.append(writeJson(name)).append(':');
                appendCanonical(node.get(name), out);
                out.append(',');
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            node.forEach(child -> {
                appendCanonical(child, out);
                out.append(',');
            });
            out.append(']');
        } else if (node.isNumber()) {
            out.append(node.decimalValue().stripTrailingZeros().toPlainString());
        } else if (node.isTextual()) {
            out.append(writeJson(node.textValue()));
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else {
            out.append(writeJson(node));
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private record StoredRequest(String requestHash, String responseBody) {
    }
}
