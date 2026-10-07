package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotencyRecord;

/**
 * 幂等去重持久化。request_id 主键即幂等键；记录与业务写入同事务提交。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("resource"),
            rs.getString("fingerprint"),
            rs.getString("actor"),
            rs.getInt("response_status"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按全局请求 ID 查询幂等记录。
     */
    public Optional<IdempotencyRecord> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 写入幂等记录（须在业务事务内调用）；request_id 冲突时抛出 DuplicateKeyException。
     */
    public void insert(IdempotencyRecord record) {
        jdbc.update("INSERT INTO idempotency_record "
                        + "(request_id, operation, resource, fingerprint, actor, response_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                record.requestId(), record.operation(), record.resource(), record.fingerprint(),
                record.actor(), record.responseStatus(), record.responseBody(),
                JdbcTimes.toDb(Instant.now()));
    }
}
