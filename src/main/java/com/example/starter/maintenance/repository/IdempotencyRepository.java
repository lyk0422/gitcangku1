package com.example.starter.maintenance.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 幂等键数据访问；仅成功写操作占键，与业务变更同事务提交。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 已占键记录快照。
     *
     * @param action         操作类型
     * @param payloadHash    规范化请求参数摘要
     * @param responseStatus 原成功响应状态码
     * @param responseBody   原成功响应体
     */
    public record Entry(String action, String payloadHash, int responseStatus, String responseBody) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Entry> find(String requestId) {
        return jdbc.query("SELECT action, payload_hash, response_status, response_body FROM idempotency_key"
                        + " WHERE request_id = ?",
                (rs, i) -> new Entry(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)),
                requestId).stream().findFirst();
    }

    public void insert(String requestId, String action, String payloadHash,
                       int responseStatus, String responseBody, long nowEpochMs) {
        jdbc.update("INSERT INTO idempotency_key (request_id, action, payload_hash, response_status, response_body, created_at_epoch_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                requestId, action, payloadHash, responseStatus, responseBody, nowEpochMs);
    }
}
