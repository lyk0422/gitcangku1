package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等记录持久化。requestId 同参重放返回首次结果，异参 409；失败请求不写入。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 幂等记录。
     *
     * @param requestId    幂等请求 ID
     * @param fingerprint  请求参数指纹
     * @param responseBody 首次成功响应体（JSON）
     * @param createdAt    记录时间（UTC）
     */
    public record IdempotencyRecord(String requestId, String fingerprint, String responseBody,
                                    Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 requestId 查询幂等记录（不加锁）。
     */
    public Optional<IdempotencyRecord> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?",
                (rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("request_id"),
                        rs.getString("fingerprint"),
                        rs.getString("response_body"),
                        JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class))),
                requestId).stream().findFirst();
    }

    /**
     * 写入幂等记录（须在业务成功的事务内调用，失败回滚即不占键）。
     */
    public void insert(String requestId, String fingerprint, String responseBody, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record (request_id, fingerprint, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                requestId, fingerprint, responseBody, JdbcTimes.toDb(createdAt));
    }
}