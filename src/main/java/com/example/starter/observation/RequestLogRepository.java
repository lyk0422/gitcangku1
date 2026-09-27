package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 幂等去重持久化：request_log 仅记录成功结果，与业务变更同事务原子提交；失败回滚不占键。
 */
@Repository
public class RequestLogRepository {

    private static final RowMapper<RequestLogEntry> ENTRY_MAPPER = (rs, rowNum) -> new RequestLogEntry(
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("request_fingerprint"),
            rs.getString("operation"),
            (Integer) rs.getObject("response_status"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbcTemplate;

    public RequestLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按请求标识查询去重记录；不存在时返回空。
     */
    public Optional<RequestLogEntry> find(String requestId) {
        return jdbcTemplate.query(
                        "SELECT request_id, fingerprint, request_fingerprint, operation, response_status, "
                                + "response_body FROM request_log WHERE request_id = ?",
                        ENTRY_MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 在写事务内先占位插入去重记录（响应暂为空），主键冲突时由数据库串行化并发同键请求。
     * 业务失败时随事务回滚，不占用该键。观测域操作的参数指纹列留空，异参检测仍按完整指纹进行。
     */
    public void insertPlaceholder(String requestId, String fingerprint, String operation) {
        jdbcTemplate.update(
                "INSERT INTO request_log (request_id, fingerprint, request_fingerprint, operation, "
                        + "response_status, response_body, created_at) "
                        + "VALUES (?, ?, NULL, ?, NULL, NULL, CURRENT_TIMESTAMP)",
                requestId, fingerprint, operation);
    }

    /**
     * 许可域占位：同时写入请求参数指纹（异参检测/重放短路）与初始完整指纹（命中许可版本后回填）。
     */
    public void insertPermitPlaceholder(String requestId, String requestFingerprint, String operation) {
        jdbcTemplate.update(
                "INSERT INTO request_log (request_id, fingerprint, request_fingerprint, operation, "
                        + "response_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, NULL, NULL, CURRENT_TIMESTAMP)",
                requestId, requestFingerprint, requestFingerprint, operation);
    }

    /**
     * 许可命中后回填含许可版本的完整 permitKey 指纹（与响应同事务提交）。
     */
    public void updateFingerprint(String requestId, String fingerprint) {
        jdbcTemplate.update(
                "UPDATE request_log SET fingerprint = ? WHERE request_id = ?",
                fingerprint, requestId);
    }

    /**
     * 业务成功后回填响应，与业务变更在同一事务内提交。
     */
    public void complete(String requestId, int responseStatus, String responseBody) {
        jdbcTemplate.update(
                "UPDATE request_log SET response_status = ?, response_body = ? WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }
}
