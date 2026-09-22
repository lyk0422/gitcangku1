package com.example.starter.repository;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等记录数据访问。仅记录成功结果；失败随业务事务一并回滚，不占用 requestId。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 幂等记录视图。
     */
    public record Record(String requestId, String operation, String requestHash,
                         int httpStatus, String responseBody) {
    }

    private static final RowMapper<Record> MAPPER = (rs, n) -> new Record(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("request_hash"),
            rs.getInt("http_status"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Record> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public void insert(String requestId, String operation, String requestHash,
                       int httpStatus, String responseBody, long createdAt) {
        jdbc.update("""
                INSERT INTO idempotency_record
                    (request_id, operation, request_hash, http_status, response_body, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, requestId, operation, requestHash, httpStatus, responseBody, createdAt);
    }
}
