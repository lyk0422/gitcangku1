package com.example.starter.artifact.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求日志的数据访问。仅记录成功的写操作，与业务变更同事务提交。
 */
@Repository
public class RequestLogRepository {

    private static final RowMapper<RequestLogRow> MAPPER = (rs, rowNum) -> new RequestLogRow(
            rs.getString("request_id"), rs.getString("request_type"), rs.getString("payload_hash"),
            rs.getInt("response_status"), rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public RequestLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求 id 查询已成功的写操作记录。
     */
    public Optional<RequestLogRow> find(String requestId) {
        List<RequestLogRow> rows = jdbc.query(
                "SELECT request_id, request_type, payload_hash, response_status, response_body"
                        + " FROM request_log WHERE request_id = ?",
                MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 写入成功请求记录；request_id 主键冲突时抛出重复键异常。
     */
    public void insert(RequestLogRow row) {
        jdbc.update(
                "INSERT INTO request_log (request_id, request_type, payload_hash, response_status,"
                        + " response_body) VALUES (?, ?, ?, ?, ?)",
                row.requestId(), row.requestType(), row.payloadHash(),
                row.responseStatus(), row.responseBody());
    }
}
