package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotencyRecord;

/**
 * 幂等记录持久化。(endpoint, request_id) 唯一；仅成功响应占键，失败事务回滚不留记录。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getLong("id"),
            rs.getString("endpoint"),
            rs.getString("request_id"),
            rs.getString("params_hash"),
            rs.getInt("response_status"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按端点与请求 ID 查询（不加锁）。
     */
    public Optional<IdempotencyRecord> find(String endpoint, String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE endpoint = ? AND request_id = ?",
                MAPPER, endpoint, requestId).stream().findFirst();
    }

    /**
     * 插入幂等记录；(endpoint, request_id) 冲突时抛出 DuplicateKeyException。
     */
    public void insert(String endpoint, String requestId, String paramsHash,
                       int responseStatus, String responseBody, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record "
                        + "(endpoint, request_id, params_hash, response_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                endpoint, requestId, paramsHash, responseStatus, responseBody, JdbcTimes.toDb(createdAt));
    }
}
