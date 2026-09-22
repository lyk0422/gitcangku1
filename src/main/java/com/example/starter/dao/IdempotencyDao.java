package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 幂等键表访问：requestId 全局唯一；键与业务变更在同一事务提交，失败回滚即不占键。
 */
@Repository
public class IdempotencyDao {

    /**
     * 幂等键记录：responseBody 在事务提交前写入，已提交的键必然携带成功响应。
     */
    public record IdempotencyRecord(String requestId, String action, String fingerprint,
                                    String responseBody) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按主键查询幂等键。
     */
    public Optional<IdempotencyRecord> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_keys WHERE request_id = ?",
                        (rs, rowNum) -> new IdempotencyRecord(
                                rs.getString("request_id"),
                                rs.getString("action"),
                                rs.getString("fingerprint"),
                                rs.getString("response_body")),
                        requestId)
                .stream().findFirst();
    }

    /**
     * 占键：并发同键时由主键约束串行化，后提交者抛出重复键异常。
     */
    public void insert(String requestId, String action, String fingerprint, Instant now) {
        jdbc.update("INSERT INTO idempotency_keys (request_id, action, fingerprint, response_body, created_at)"
                        + " VALUES (?, ?, ?, NULL, ?)",
                requestId, action, fingerprint, Timestamp.from(now));
    }

    /**
     * 写入成功响应体；与业务变更在同一事务提交。
     */
    public void complete(String requestId, String responseBody) {
        jdbc.update("UPDATE idempotency_keys SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }
}
