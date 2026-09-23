package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求持久化访问：成功结果与业务变更同事务保存，应用重启后幂等语义仍成立。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRow> MAPPER = (rs, rowNum) -> new IdempotencyRow(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("params_fingerprint"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等请求行。
     *
     * @param requestId         幂等请求标识
     * @param operation         操作类型：GRANT / WRITE / REVOKE
     * @param paramsFingerprint 规范化参数指纹，用于检测同 requestId 参数变更
     * @param responseBody      成功响应快照（JSON）
     */
    public record IdempotencyRow(String requestId, String operation, String paramsFingerprint, String responseBody) {
    }

    public Optional<IdempotencyRow> find(String requestId) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT request_id, operation, params_fingerprint, response_body"
                        + " FROM idempotency_request WHERE request_id = ?",
                MAPPER, requestId);
        return rows.stream().findFirst();
    }

    public void insert(String requestId, String operation, String paramsFingerprint, String responseBody) {
        jdbc.update(
                "INSERT INTO idempotency_request (request_id, operation, params_fingerprint, response_body)"
                        + " VALUES (?, ?, ?, ?)",
                requestId, operation, paramsFingerprint, responseBody);
    }
}
