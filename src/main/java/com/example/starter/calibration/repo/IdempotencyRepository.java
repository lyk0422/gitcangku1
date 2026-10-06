package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等去重记录持久化。request_id 为主键；身份要素（操作、操作者、资源、参数 JSON）
 * 分列存储并结构化比较，避免分隔符碰撞；与业务写入同事务提交，失败不占键。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 幂等记录。
     *
     * @param requestId    客户端请求 ID（幂等键）
     * @param operation    操作类型
     * @param actor        操作者
     * @param resource     资源标识
     * @param paramsJson   全部参数与期望版本的 JSON
     * @param responseJson 首次成功的完整响应体 JSON
     * @param httpStatus   首次响应的 HTTP 状态码
     * @param createdAt    记录创建时间（UTC）
     */
    public record IdempotencyRecord(String requestId, String operation, String actor, String resource,
                                    String paramsJson, String responseJson, int httpStatus,
                                    Instant createdAt) {
    }

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("actor"),
            rs.getString("resource"),
            rs.getString("params_json"),
            rs.getString("response_json"),
            rs.getInt("http_status"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求 ID 查询（不加锁）。
     */
    public Optional<IdempotencyRecord> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }

    /**
     * 写入幂等记录；request_id 冲突时抛出 DuplicateKeyException（由调用方转为重放或 409）。
     */
    public void insert(IdempotencyRecord record) {
        jdbc.update("INSERT INTO idempotency_record "
                        + "(request_id, operation, actor, resource, params_json, response_json, http_status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                record.requestId(), record.operation(), record.actor(), record.resource(),
                record.paramsJson(), record.responseJson(), record.httpStatus(),
                JdbcTimes.toDb(record.createdAt()));
    }

    /**
     * 业务成功后补写首次响应快照（须在持有占位记录的同一事务内调用）。
     */
    public void updateResponse(String requestId, String responseJson, int httpStatus) {
        jdbc.update("UPDATE idempotency_record SET response_json = ?, http_status = ? "
                        + "WHERE request_id = ?",
                responseJson, httpStatus, requestId);
    }
}
