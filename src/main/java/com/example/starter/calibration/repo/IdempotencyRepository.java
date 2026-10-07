package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotencyRecord;

/**
 * 幂等去重记录持久化。request_id 全局唯一；占位插入与业务写入同事务提交，
 * 业务失败随事务回滚，不占键。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("actor"),
            rs.getString("resource"),
            rs.getString("params_fingerprint"),
            (Integer) rs.getObject("response_status", Integer.class),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求 ID 查询（不加锁）。
     */
    public Optional<IdempotencyRecord> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 插入占位记录（响应字段为 NULL，须在事务内调用）；request_id 冲突时抛出 DuplicateKeyException。
     */
    public long insertPending(String requestId, String operation, String actor, String resource,
                              String paramsFingerprint, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO idempotency_record "
                            + "(request_id, operation, actor, resource, params_fingerprint, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, operation);
            ps.setString(3, actor);
            ps.setString(4, resource);
            ps.setString(5, paramsFingerprint);
            ps.setObject(6, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 业务成功后回写首次完整响应（须在同事务内调用）。
     */
    public void complete(String requestId, int responseStatus, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET response_status = ?, response_body = ? WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }
}
