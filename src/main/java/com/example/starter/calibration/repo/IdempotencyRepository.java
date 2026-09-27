package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotencyRecord;

/**
 * 幂等请求记录持久化。requestId 唯一；占位记录与业务写入同事务提交或回滚，
 * 因此失败请求不占键。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("operation"),
            rs.getString("actor"),
            rs.getInt("response_status"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 requestId 查询（不加锁）；记录提交后不可变。
     */
    public Optional<IdempotencyRecord> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 插入占位记录（response_status=0）；requestId 冲突时抛出 DuplicateKeyException。
     */
    public void insertPlaceholder(String requestId, String fingerprint, String operation,
                                  String actor, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record "
                        + "(request_id, fingerprint, operation, actor, response_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, 0, '', ?)",
                requestId, fingerprint, operation, actor, JdbcTimes.toDb(createdAt));
    }

    /**
     * 在同事务内将占位记录补全为首次成功响应快照。
     */
    public void complete(String requestId, int responseStatus, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET response_status = ?, response_body = ? "
                        + "WHERE request_id = ?", responseStatus, responseBody, requestId);
    }
}
