package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotencyRecord;

/**
 * 幂等去重记录持久化。request_id 主键串行化并发同键请求；
 * 占位行与业务在同一事务提交，业务失败整体回滚（失败不占键）。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("request_id"),
            rs.getString("actor"),
            rs.getString("fingerprint"),
            (Integer) rs.getObject("response_status"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入占位记录（须在事务内调用，响应待业务完成后回填）；
     * request_id 已存在时抛出 DuplicateKeyException，由调用方转为重放或 409。
     */
    public void insertPlaceholder(String requestId, String actor, String fingerprint, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record (request_id, actor, fingerprint, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                requestId, actor, fingerprint, JdbcTimes.toDb(createdAt));
    }

    /**
     * 按请求 ID 查询（不加锁）。
     */
    public Optional<IdempotencyRecord> findById(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 回填首次完整响应（须在同一事务内、业务成功后调用）。
     */
    public void complete(String requestId, int responseStatus, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET response_status = ?, response_body = ? WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }
}
