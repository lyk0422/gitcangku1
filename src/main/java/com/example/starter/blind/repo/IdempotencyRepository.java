package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 写操作幂等记录数据访问；只记录成功结果，失败不写入（随业务事务回滚）。
 */
@Repository
public class IdempotencyRepository {

    /** 已存储的成功结果，重放时原样返回状态码与响应体。 */
    public record IdempotentRow(
            String requestId,
            String actorId,
            String role,
            String operation,
            String fingerprint,
            int responseStatus,
            String responseBody,
            long createdAt) {
    }

    private static final RowMapper<IdempotentRow> MAPPER = (rs, n) -> new IdempotentRow(
            rs.getString("request_id"),
            rs.getString("actor_id"),
            rs.getString("role"),
            rs.getString("operation"),
            rs.getString("fingerprint"),
            rs.getInt("response_status"),
            rs.getString("response_body"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public IdempotentRow findByRequestId(String requestId) {
        List<IdempotentRow> rows = jdbc.query(
                "SELECT request_id, actor_id, role, operation, fingerprint, response_status, "
                        + "response_body, created_at FROM idempotent_request WHERE request_id = ?",
                MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行级锁定幂等键；新键无行可锁时依赖主键唯一约束兜底并发。
     */
    public IdempotentRow lockByRequestId(String requestId) {
        List<IdempotentRow> rows = jdbc.query(
                "SELECT request_id, actor_id, role, operation, fingerprint, response_status, "
                        + "response_body, created_at FROM idempotent_request WHERE request_id = ? FOR UPDATE",
                MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(IdempotentRow row) {
        jdbc.update("INSERT INTO idempotent_request ("
                        + "request_id, actor_id, role, operation, fingerprint, "
                        + "response_status, response_body, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.requestId(), row.actorId(), row.role(), row.operation(), row.fingerprint(),
                row.responseStatus(), row.responseBody(), row.createdAt());
    }

    /**
     * 业务成功后在同一事务内回填真实响应；失败时整个事务回滚，不残留占位行。
     */
    public void updateResult(String requestId, int responseStatus, String responseBody) {
        jdbc.update("UPDATE idempotent_request SET response_status = ?, response_body = ? "
                + "WHERE request_id = ?", responseStatus, responseBody, requestId);
    }
}
