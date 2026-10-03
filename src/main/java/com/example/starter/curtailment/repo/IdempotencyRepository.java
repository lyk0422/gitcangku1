package com.example.starter.curtailment.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 幂等记录仓储：按（操作类型, requestId）固化参数指纹与响应，支持同参重放、异参 409。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 幂等记录行。
     *
     * @param operation    操作类型
     * @param requestId    客户端请求标识
     * @param fingerprint  参数指纹（SHA-256 十六进制）
     * @param httpStatus   首次请求 HTTP 状态码
     * @param responseBody 首次请求响应报文（JSON）
     */
    public record IdempotencyRecord(String operation, String requestId, String fingerprint, int httpStatus,
                                    String responseBody) {
    }

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("operation"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getInt("http_status"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按操作与请求标识查询。 */
    public Optional<IdempotencyRecord> find(String operation, String requestId) {
        List<IdempotencyRecord> rows = jdbc.query(
                "SELECT * FROM idempotency_record WHERE operation = ? AND request_id = ?",
                MAPPER, operation, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 插入占位记录（响应待补）。并发同键时抛出 DuplicateKeyException，由调用方回读已提交记录。
     */
    public void insertPlaceholder(String operation, String requestId, String fingerprint, Instant now) {
        jdbc.update("INSERT INTO idempotency_record (operation, request_id, fingerprint, http_status, response_body, created_at) "
                + "VALUES (?, ?, ?, 0, '', ?)", operation, requestId, fingerprint, JdbcTimes.toSql(now));
    }

    /** 回填首次请求的响应与状态码。 */
    public void complete(String operation, String requestId, int httpStatus, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET http_status = ?, response_body = ? WHERE operation = ? AND request_id = ?",
                httpStatus, responseBody, operation, requestId);
    }
}