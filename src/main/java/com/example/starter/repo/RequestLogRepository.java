package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.example.starter.domain.RequestLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求日志仓储：requestId 主键去重，失败事务随回滚消失。
 */
@Repository
public class RequestLogRepository {

    private static final RowMapper<RequestLog> MAPPER = (rs, n) -> new RequestLog(
            rs.getString("request_id"), rs.getString("operation"), rs.getString("payload_hash"),
            rs.getInt("http_status"), rs.getString("response_body"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public RequestLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RequestLog> findById(String requestId) {
        return jdbc.query("SELECT * FROM request_log WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public void insert(String requestId, String operation, String payloadHash, int httpStatus,
            String responseBody, Instant createdAt) {
        jdbc.update("INSERT INTO request_log (request_id, operation, payload_hash, http_status, response_body,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                requestId, operation, payloadHash, httpStatus, responseBody, Timestamp.from(createdAt));
    }
}