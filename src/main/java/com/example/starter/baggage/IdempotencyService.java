package com.example.starter.baggage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
     * 在幂等保护下执行写操作。
     *
     * @param requestId     全局唯一请求标识
     * @param operation     操作类型
     * @param successStatus 成功响应的 HTTP 状态码（随记录保存，供审计）
     * @param payload       请求参数（不含 requestId，调用方需已规范化），用于计算摘要
     * @param resultType    成功结果类型，用于重放时反序列化
     * @param action        业务动作，成功结果与去重记录在同一事务提交
     * @return 已存储的成功结果（重放）或本次执行结果
     */
    public <T> T execute(String requestId, String operation, int successStatus,
                         Object payload, Class<T> resultType, Supplier<T> action) {
        String hash = sha256(operation + "\n" + canonicalJson(payload));
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
                                "INSERT INTO request_log (request_id, operation, request_hash, response_status, response_body)"
                                        + " VALUES (?, ?, ?, ?, ?)",
                                requestId, operation, hash, successStatus, writeJson(result));
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
     * 结构化参数摘要：操作类型与资源、全参数、期望版本一起参与比较。
     * 十进制按值规范化（1.0 与 1.00 视为相同），文本保持原样不做任何折叠。
     */
    private String canonicalJson(Object payload) {
        JsonNode tree = normalizeDecimals(objectMapper.valueToTree(payload));
        return writeJson(tree);
    }

    /** 递归把浮点数值规范化为去尾零的 BigDecimal，对象与数组就地处理（基于自有副本）。 */
    private static JsonNode normalizeDecimals(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.fields().forEachRemaining(entry -> entry.setValue(normalizeDecimals(entry.getValue())));
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, normalizeDecimals(array.get(i)));
            }
        } else if (node.isFloatingPointNumber()) {
            return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        }
        return node;
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
