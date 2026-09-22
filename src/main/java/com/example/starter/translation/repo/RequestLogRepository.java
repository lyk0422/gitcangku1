package com.example.starter.translation.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 写操作幂等去重记录的数据访问。
 */
@Repository
public class RequestLogRepository {

    private final JdbcTemplate jdbc;

    public RequestLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等记录。
     *
     * @param requestId   全局唯一请求 ID
     * @param requestHash 请求参数摘要
     * @param status      原成功响应 HTTP 状态码
     * @param response    原成功响应正文（JSON）
     */
    public record RequestLogRow(String requestId, String requestHash, int status, String response) {
    }

    public Optional<RequestLogRow> find(String requestId) {
        return jdbc.query("SELECT request_id, request_hash, status, response FROM request_log WHERE request_id = ?",
                (rs, i) -> new RequestLogRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)),
                requestId).stream().findFirst();
    }

    public void insert(String requestId, String requestHash, int status, String response, Instant now) {
        jdbc.update("INSERT INTO request_log (request_id, request_hash, status, response, created_at) VALUES (?, ?, ?, ?, ?)",
                requestId, requestHash, status, response, Timestamp.from(now));
    }
}
