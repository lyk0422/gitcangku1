package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 幂等去重记录数据访问。request_id 全局唯一，业务变更与去重记录在同一事务提交。
 */
@Repository
public class IdempotencyRepository {

    public record IdempotencyRecord(String requestId, String api, String fingerprint, String responseBody) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotencyRecord> find(String requestId) {
        return jdbc.query("SELECT request_id, api, fingerprint, response_body FROM idempotency_record"
                        + " WHERE request_id = ?",
                (rs, rowNum) -> new IdempotencyRecord(rs.getString("request_id"), rs.getString("api"),
                        rs.getString("fingerprint"), rs.getString("response_body")),
                requestId).stream().findFirst();
    }

    public void insert(String requestId, String api, String fingerprint, String responseBody) {
        jdbc.update("INSERT INTO idempotency_record (request_id, api, fingerprint, response_body)"
                + " VALUES (?, ?, ?, ?)", requestId, api, fingerprint, responseBody);
    }
}
