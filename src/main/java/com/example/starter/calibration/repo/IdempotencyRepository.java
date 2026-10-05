package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等去重持久化。与业务写入同事务原子提交；仅成功请求占键，失败回滚不占键。
 * 先插入占位行（response_body 为 NULL）再执行业务并回填响应，
 * 并发同键请求在主键上串行化，不泄露唯一键异常。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 已存储的幂等记录。
     *
     * @param fingerprint  结构化指纹（规范化 JSON）
     * @param responseBody 首次成功的完整响应体（JSON）
     */
    public record Stored(String fingerprint, String responseBody) {
    }

    private static final RowMapper<Stored> MAPPER = (rs, rowNum) -> new Stored(
            rs.getString("fingerprint"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求 ID 查询并加行锁（须在事务内调用），用于重放判定与并发串行化。
     */
    public Optional<Stored> findForUpdate(String requestId) {
        return jdbc.query("SELECT fingerprint, response_body FROM idempotency_record "
                        + "WHERE request_id = ? FOR UPDATE", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 插入占位记录（response_body 为 NULL）；同键并发插入时抛出 DuplicateKeyException。
     */
    public void insertPlaceholder(String requestId, String operation, String actor, String resource,
                                  String fingerprint) {
        jdbc.update("INSERT INTO idempotency_record "
                        + "(request_id, operation, actor, resource, fingerprint, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, NULL, ?)",
                requestId, operation, actor, resource, fingerprint, JdbcTimes.toDb(Instant.now()));
    }

    /**
     * 回填首次成功的完整响应体（须在占位插入的同一事务内调用）。
     */
    public void storeResponse(String requestId, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }
}
