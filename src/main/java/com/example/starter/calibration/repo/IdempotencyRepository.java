package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotencyRecord;

/**
 * 幂等记录持久化。requestId 全局唯一；仅在操作成功时写入，失败随事务回滚不占键。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("actor"),
            rs.getString("fingerprint"),
            rs.getInt("status_code"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 占键：插入幂等记录（响应快照待成功后回填）；
     * requestId 已存在时抛出 DuplicateKeyException。
     */
    public void insertNew(String requestId, String operation, String actor,
                          String fingerprint, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record "
                        + "(request_id, operation, actor, fingerprint, status_code, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, 0, '', ?)",
                requestId, operation, actor, fingerprint, JdbcTimes.toDb(createdAt));
    }

    /**
     * 成功后回填响应快照（须与占键在同一事务内）。
     */
    public void storeResponse(String requestId, int statusCode, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET status_code = ?, response_body = ? WHERE request_id = ?",
                statusCode, responseBody, requestId);
    }

    /**
     * 按 requestId 查询幂等记录。
     */
    public Optional<IdempotencyRecord> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }
}
