package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotencyRecord;

/**
 * 幂等请求记录持久化。request_id 主键保证同键并发只有一个占位成功；
 * 记录与业务写入同事务提交或回滚，失败不占键。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("identity_json"),
            rs.getString("response_json"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求 ID 查询（不加锁）。
     */
    public Optional<IdempotencyRecord> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 插入占位记录（响应为空，提交前写回）；request_id 冲突时抛出 DuplicateKeyException。
     */
    public void insert(String requestId, String operation, String identityJson, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record (request_id, operation, identity_json, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                requestId, operation, identityJson, JdbcTimes.toDb(createdAt));
    }

    /**
     * 写回首次成功的完整响应 JSON（须在同一事务内调用）。
     */
    public void storeResponse(String requestId, String responseJson) {
        jdbc.update("UPDATE idempotency_record SET response_json = ? WHERE request_id = ?",
                responseJson, requestId);
    }
}
