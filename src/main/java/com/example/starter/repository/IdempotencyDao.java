package com.example.starter.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 写操作幂等记录访问：仅成功请求占键。
 */
@Repository
public class IdempotencyDao {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record RecordRow(String requestId, String operation, String fingerprint,
                            int statusCode, String responseBody) {
    }

    public Optional<RecordRow> find(String requestId) {
        List<RecordRow> rows = jdbcTemplate.query(
                "SELECT request_id, operation, fingerprint, status_code, response_body "
                        + "FROM idempotency_record WHERE request_id = ?",
                (rs, n) -> new RecordRow(rs.getString("request_id"), rs.getString("operation"),
                        rs.getString("fingerprint"), rs.getInt("status_code"),
                        rs.getString("response_body")),
                requestId);
        return rows.stream().findFirst();
    }

    /**
     * 插入幂等记录；唯一键冲突（并发同 requestId）返回 false，由调用方改走重放。
     */
    public boolean tryInsert(String requestId, String operation, String fingerprint,
                             int statusCode, String responseBody) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO idempotency_record "
                            + "(request_id, operation, fingerprint, status_code, response_body) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    requestId, operation, fingerprint, statusCode, responseBody);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
