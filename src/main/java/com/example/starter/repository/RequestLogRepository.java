package com.example.starter.repository;

import com.example.starter.domain.RequestLogRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求日志表 request_log 的 JDBC 仓储。
 */
@Repository
public class RequestLogRepository {

    private static final RowMapper<RequestLogRecord> MAPPER = (rs, n) -> new RequestLogRecord(
            rs.getString("request_id"),
            rs.getString("request_type"),
            rs.getString("request_hash"),
            rs.getString("response_json"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public RequestLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求键查询已受理记录。
     */
    public Optional<RequestLogRecord> findById(String requestId) {
        List<RequestLogRecord> rows = jdbc.query(
                "SELECT * FROM request_log WHERE request_id = ?", MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 成功请求占键并保存响应快照。
     */
    public void insert(String requestId, String requestType, String requestHash,
                       String responseJson, Instant createdAt) {
        jdbc.update("INSERT INTO request_log (request_id, request_type, request_hash, response_json, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                requestId, requestType, requestHash, responseJson, Timestamp.from(createdAt));
    }
}