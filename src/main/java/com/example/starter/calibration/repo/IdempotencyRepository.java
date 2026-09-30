package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 请求幂等记录持久化。仅记录“已提交成功”的请求：业务失败随事务回滚，不占用 requestId。
 * 同参重放返回首次响应；异参重放由服务层比对 requestHash 后判 409。
 *
 * @param requestId    请求幂等键（X-Request-Id）
 * @param method       HTTP 方法
 * @param path         请求路径
 * @param actor        请求角色（X-Actor-Id），无该头为 null
 * @param requestHash  同参归一指纹 SHA-256
 * @param statusCode   首次成功响应 HTTP 状态码
 * @param responseBody 首次成功响应体原文
 * @param createdAt    首次请求时间（UTC）
 */
@Repository
public class IdempotencyRepository {

    /** 一条已落盘的幂等记录。 */
    public record IdempotentRequest(
            String requestId,
            String method,
            String path,
            String actor,
            String requestHash,
            Integer statusCode,
            String responseBody,
            Instant createdAt) {
    }

    private static final RowMapper<IdempotentRequest> MAPPER = (rs, rowNum) -> new IdempotentRequest(
            rs.getString("request_id"),
            rs.getString("method"),
            rs.getString("path"),
            rs.getString("actor"),
            rs.getString("request_hash"),
            (Integer) rs.getObject("status_code"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 先插入占位记录（status_code/response_body 为 NULL），占用 requestId 并串行化同键并发请求；
     * 键冲突抛出 DuplicateKeyException，由服务层处理重放或冲突。
     */
    public void insertPlaceholder(String requestId, String method, String path, String actor,
                                  String requestHash, Instant createdAt) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO request_log "
                            + "(request_id, method, path, actor, request_hash, status_code, response_body, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, NULL, NULL, ?)");
            ps.setString(1, requestId);
            ps.setString(2, method);
            ps.setString(3, path);
            if (actor == null) {
                ps.setNull(4, java.sql.Types.VARCHAR);
            } else {
                ps.setString(4, actor);
            }
            ps.setString(5, requestHash);
            ps.setObject(6, JdbcTimes.toDb(createdAt));
            return ps;
        });
    }

    /** 业务成功提交时回填首次响应。 */
    public void complete(String requestId, int statusCode, String responseBody) {
        jdbc.update("UPDATE request_log SET status_code = ?, response_body = ? WHERE request_id = ?",
                statusCode, responseBody, requestId);
    }

    /** 按 requestId 查询（不加锁）。 */
    public Optional<IdempotentRequest> find(String requestId) {
        return jdbc.query("SELECT * FROM request_log WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /** 按 requestId 查询并对行加锁（须在事务内），用于并发同键请求等待首次结果。 */
    public Optional<IdempotentRequest> findForUpdate(String requestId) {
        return jdbc.query("SELECT * FROM request_log WHERE request_id = ? FOR UPDATE", MAPPER, requestId)
                .stream().findFirst();
    }
}
