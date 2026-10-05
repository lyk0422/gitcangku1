package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotencyRecord;

/**
 * 幂等去重持久化。request_id 全局唯一；记录与业务写入同事务提交，
 * 业务失败随事务回滚而不占键；并发同键插入由唯一约束串行化并抛出 DuplicateKeyException。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按请求 ID 查询（不加锁）。 */
    public Optional<IdempotencyRecord> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 先占位插入（响应快照为空串，业务成功后由 saveResponse 回填）；
     * request_id 冲突时抛出 DuplicateKeyException。
     */
    public void insert(String requestId, String fingerprint) {
        jdbc.update("INSERT INTO idempotency_record (request_id, fingerprint, response_body, created_at) "
                        + "VALUES (?, ?, '', ?)",
                requestId, fingerprint, JdbcTimes.toDb(Instant.now()));
    }

    /** 回填首次成功的完整响应快照（须与 insert 同事务）。 */
    public void saveResponse(String requestId, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }
}
