package com.example.starter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 幂等记录表 idempotency_record 数据访问。
 * (scope, request_id) 唯一：签发与放行分别在各自作用域内接受 requestId。
 */
@Repository
public class IdempotencyRepository {

    /** 幂等作用域：证书签发。 */
    public static final String SCOPE_ISSUE = "ISSUE";
    /** 幂等作用域：批次放行。 */
    public static final String SCOPE_RELEASE = "RELEASE";

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 一条幂等记录：请求指纹（用于识别异参重放）与首次成功响应的 JSON。
     *
     * @param scope        作用域
     * @param requestId    请求幂等键
     * @param fingerprint  请求参数指纹（本题为批次编号）
     * @param responseJson 首次成功响应序列化 JSON
     * @param createdAt    记录时刻
     */
    public record IdempotencyRecord(String scope, String requestId, String fingerprint,
                                    String responseJson, LocalDateTime createdAt) {
    }

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = (rs, rowNum) ->
            new IdempotencyRecord(
                    rs.getString("scope"),
                    rs.getString("request_id"),
                    rs.getString("fingerprint"),
                    rs.getString("response_json"),
                    rs.getTimestamp("created_at").toLocalDateTime());

    /**
     * 查询已有幂等记录。
     */
    public Optional<IdempotencyRecord> find(String scope, String requestId) {
        return jdbcTemplate.query(
                "SELECT scope, request_id, fingerprint, response_json, created_at "
                        + "FROM idempotency_record WHERE scope = ? AND request_id = ?",
                ROW_MAPPER, scope, requestId).stream().findFirst();
    }

    /**
     * 写入首次成功请求的幂等记录；(scope, request_id) 唯一约束在并发重放下兜底。
     */
    public void insert(String scope, String requestId, String fingerprint,
                       String responseJson, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO idempotency_record "
                        + "(scope, request_id, fingerprint, response_json, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                scope, requestId, fingerprint, responseJson, Timestamp.valueOf(createdAt));
    }
}
