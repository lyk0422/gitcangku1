package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotencyRecord;

/**
 * 幂等去重记录持久化。request_id 为主键；记录与业务写入同事务提交，
 * 业务失败回滚后键自动释放（失败不占键）。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("fingerprint"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("completed_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求键查询（不加锁）。
     */
    public Optional<IdempotencyRecord> findById(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 插入处理中占位记录（须在业务事务内调用）；键冲突时抛出 DuplicateKeyException。
     */
    public void insertPending(String requestId, String operation, String fingerprint, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record "
                        + "(request_id, operation, fingerprint, response_body, created_at, completed_at) "
                        + "VALUES (?, ?, ?, NULL, ?, NULL)",
                requestId, operation, fingerprint, JdbcTimes.toDb(createdAt));
    }

    /**
     * 写入首次成功响应（须在同一事务内调用，随业务一起提交）。
     */
    public void complete(String requestId, String responseBody, Instant completedAt) {
        jdbc.update("UPDATE idempotency_record SET response_body = ?, completed_at = ? WHERE request_id = ?",
                responseBody, JdbcTimes.toDb(completedAt), requestId);
    }
}
