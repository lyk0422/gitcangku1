package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotencyRecord;

/**
 * 幂等去重持久化。request_id 为主键；记录与业务写入同事务提交，
 * 冲突时抛出 DuplicateKeyException 由上层转换为重放或 409。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("operator"),
            rs.getString("resource"),
            rs.getString("params_json"),
            rs.getInt("response_status"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 requestId 查询（不加锁）。
     */
    public Optional<IdempotencyRecord> findById(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 插入幂等记录（须与业务写入同事务）；request_id 冲突时抛出 DuplicateKeyException。
     * 先插入占位（response_status=0），业务成功后由 complete 写回完整响应；
     * 业务失败回滚则占位一并消失，失败不占键。
     */
    public void insert(IdempotencyRecord record) {
        jdbc.update("INSERT INTO idempotency_record "
                        + "(request_id, operation, operator, resource, params_json, response_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                record.requestId(), record.operation(), record.operator(), record.resource(),
                record.paramsJson(), record.responseStatus(), record.responseBody(),
                JdbcTimes.toDb(Instant.now()));
    }

    /**
     * 业务成功后写回完整响应（须与业务写入同事务）。
     */
    public void complete(String requestId, int responseStatus, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET response_status = ?, response_body = ? WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }
}
