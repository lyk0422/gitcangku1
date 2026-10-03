package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求键表访问；记录只随业务事务一起提交，事务失败即不消费请求键。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record IdemRow(String operation, String requestId, String payloadHash, String responseBody) {
    }

    public Optional<IdemRow> find(String operation, String requestId) {
        return jdbc.sql("SELECT operation, request_id, payload_hash, response_body"
                + " FROM idempotency_record WHERE operation = :o AND request_id = :r")
                .param("o", operation)
                .param("r", requestId)
                .query((rs, n) -> new IdemRow(
                        rs.getString("operation"),
                        rs.getString("request_id"),
                        rs.getString("payload_hash"),
                        rs.getString("response_body")))
                .optional();
    }

    public void insert(String operation, String requestId, String payloadHash,
            String responseBody, Instant createdAt) {
        jdbc.sql("INSERT INTO idempotency_record (operation, request_id, payload_hash, response_body, created_at)"
                + " VALUES (:o, :r, :h, :b, :t)")
                .param("o", operation)
                .param("r", requestId)
                .param("h", payloadHash)
                .param("b", responseBody)
                .param("t", Timestamp.from(createdAt))
                .update();
    }
}