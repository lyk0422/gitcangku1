package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotencyRecord;

/**
 * 幂等记录持久化。(scope, request_id) 唯一；仅在业务成功提交后写入，失败事务回滚不占键。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getLong("id"),
            rs.getString("scope"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getInt("response_status"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按作用域与幂等键查询（不加锁）。
     */
    public Optional<IdempotencyRecord> find(String scope, String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE scope = ? AND request_id = ?",
                MAPPER, scope, requestId)
                .stream().findFirst();
    }

    /**
     * 写入首次成功结果；(scope, request_id) 冲突时抛出 DuplicateKeyException。
     */
    public void insert(String scope, String requestId, String fingerprint,
                       int responseStatus, String responseBody, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record "
                        + "(scope, request_id, fingerprint, response_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                scope, requestId, fingerprint, responseStatus, responseBody, JdbcTimes.toDb(createdAt));
    }
}
