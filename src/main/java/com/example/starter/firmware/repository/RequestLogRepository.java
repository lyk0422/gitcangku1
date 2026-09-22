package com.example.starter.firmware.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 幂等去重表数据访问。
 */
@Repository
public class RequestLogRepository {

    /**
     * 已登记请求记录。
     *
     * @param requestId    请求 ID
     * @param fingerprint  请求参数指纹
     * @param httpStatus   首次成功响应的 HTTP 状态码
     * @param responseBody 首次成功响应报文（JSON）
     */
    public record RequestLogRow(String requestId, String fingerprint, int httpStatus, String responseBody) {
    }

    private static final RowMapper<RequestLogRow> MAPPER = (rs, rowNum) -> new RequestLogRow(
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getInt("http_status"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public RequestLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求 ID 查询已登记记录。
     */
    public Optional<RequestLogRow> findById(String requestId) {
        return jdbc.query("SELECT request_id, fingerprint, http_status, response_body"
                        + " FROM request_log WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 登记成功结果；request_id 冲突时抛出 DuplicateKeyException。
     */
    public void insert(String requestId, String action, String fingerprint, int httpStatus, String responseBody) {
        jdbc.update("INSERT INTO request_log (request_id, action, fingerprint, http_status, response_body)"
                        + " VALUES (?, ?, ?, ?, ?)",
                requestId, action, fingerprint, httpStatus, responseBody);
    }
}
