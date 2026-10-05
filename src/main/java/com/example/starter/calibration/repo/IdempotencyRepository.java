package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotencyRecord;

/**
 * 幂等请求持久化。request_id 主键保证同一请求键最多一条记录；
 * 记录与业务写入在同一事务提交，业务失败回滚即释放请求键。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 抢占请求键（响应为空，表示处理中）；request_id 冲突时抛出 DuplicateKeyException。
     */
    public void insert(String requestId, String fingerprint, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record (request_id, fingerprint, created_at) VALUES (?, ?, ?)",
                requestId, fingerprint, JdbcTimes.toDb(createdAt));
    }

    /**
     * 按请求键查询（不加锁）。
     */
    public Optional<IdempotencyRecord> findById(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 写入首次成功响应（须在业务成功后的同一事务内调用）。
     */
    public void storeResponse(String requestId, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }
}
