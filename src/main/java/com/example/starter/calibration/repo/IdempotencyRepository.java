package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 写操作幂等去重持久化。request_id 为主键；
 * 去重记录与业务写入在同一事务提交，业务失败整体回滚，因此失败请求可复用同一 requestId。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 幂等记录。
     *
     * @param requestId    全局请求 ID（幂等键）
     * @param operation    操作类型
     * @param fingerprint  身份指纹（SHA-256 hex）
     * @param responseBody 首次成功的完整响应 JSON
     * @param createdAt    记录创建时间（UTC）
     */
    public record IdempotencyRecord(String requestId, String operation, String fingerprint,
                                    String responseBody, Instant createdAt) {
    }

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("fingerprint"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求 ID 查询（不加锁）。
     */
    public Optional<IdempotencyRecord> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 插入幂等记录；request_id 冲突时抛出 DuplicateKeyException（由调用方转为业务结果）。
     * 先占位（response_body 为 NULL），业务成功后同事务回写响应。
     */
    public void insert(String requestId, String operation, String fingerprint,
                       String responseBody, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record (request_id, operation, fingerprint, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                requestId, operation, fingerprint, responseBody, JdbcTimes.toDb(createdAt));
    }

    /**
     * 业务成功后回写首次完整响应（与业务写入同事务提交）。
     */
    public void updateResponse(String requestId, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }
}
