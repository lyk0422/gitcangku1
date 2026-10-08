package com.example.starter.baggage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 幂等去重服务：所有写操作携带全局唯一 requestId 与非空操作者。
 * 同键重放时按“操作 + 操作者 + 资源标识 + 全参数（含期望版本）”结构化比较：
 * 完全一致重放原成功结果，任一不同返回 409；失败请求不占键，
 * 业务变更与去重记录在同一事务中原子提交。唯一键竞争按重放处理，不上抛 500。
 * 十进制字段由 JSON 数值按值比较，文本字段原样参与比较。
 */
@Service
public class IdempotencyService {

    /** 无操作者信息的旧入口使用的占位操作者。 */
    private static final String LEGACY_OPERATOR = "LEGACY";

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

    /** 旧签名兼容：无操作者信息时以占位操作者参与摘要。 */
    public <T> T execute(String requestId, String operation, int successStatus,
                         Object payload, Class<T> resultType, Supplier<T> action) {
        return execute(requestId, operation, LEGACY_OPERATOR, successStatus, payload, resultType, action);
    }

    /**
     * 在幂等保护下执行写操作。
     *
     * @param requestId     全局唯一请求标识
     * @param operation     操作类型（参与摘要，同 requestId 跨操作视为异参）
     * @param operator      非空操作者标识（原样参与摘要）
     * @param successStatus 成功响应的 HTTP 状态码（随记录保存，供审计）
     * @param payload       请求参数（不含 requestId，调用方需已规范化），用于计算摘要
     * @param resultType    成功结果类型，用于重放时反序列化
     * @param action        业务动作，成功结果与去重记录在同一事务提交
     * @return 已存储的成功结果（重放）或本次执行结果
     */
    public <T> T execute(String requestId, String operation, String operator, int successStatus,
                         Object payload, Class<T> resultType, Supplier<T> action) {
        String normalizedOperator = operator == null || operator.isBlank()
                ? LEGACY_OPERATOR : operator;
        String hash = sha256(operation + "|" + normalizedOperator + "|" + writeJson(payload));
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
                                requestId, operation, normalizedOperator, hash, successStatus, writeJson(result));
                        return result;
                    });
                } catch (DuplicateKeyException duplicate) {
                    // 唯一主键兜底：并发竞争下同键记录已由其他事务写入，按已存储结果处理而非报 500
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
            throw ApiException.conflict("requestId 已被使用且操作或请求参数不一致");
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
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private record StoredRequest(String requestHash, String responseBody) {
    }
}
