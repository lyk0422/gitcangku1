package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotencyRecord;

/**
 * 幂等请求持久化。request_id 主键保证并发下同键最多一条记录；
 * 先插入占位行占键，操作成功后在同一事务内回填首次结果，失败回滚则不占键。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("params_hash"),
            rs.getInt("response_status"),
            rs.getString("response_body"),
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
     * 插入占位行占键；并发同键时抛出 DuplicateKeyException。
     */
    public void insertPlaceholder(String requestId, String operation, String paramsHash, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record "
                        + "(request_id, operation, params_hash, response_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, 0, '', ?)",
                requestId, operation, paramsHash, JdbcTimes.toDb(createdAt));
    }

    /**
     * 回填首次结果（须在占键后的同一事务内调用）。
     */
    public void complete(String requestId, int responseStatus, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET response_status = ?, response_body = ? WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }
}