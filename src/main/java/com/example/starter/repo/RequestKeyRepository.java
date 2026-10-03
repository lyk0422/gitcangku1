package com.example.starter.repo;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求键存取：与业务写入同事务，事务回滚时请求键一并回滚，不被失败消费。
 */
@Repository
public class RequestKeyRepository {

    /**
     * 已受理的请求键记录。
     *
     * @param operation 操作类型
     * @param fingerprint 业务参数指纹
     * @param responseBody 成功响应快照（JSON）；事务未完成时为 null
     */
    public record Entry(String operation, String fingerprint, String responseBody) {
    }

    private final JdbcTemplate jdbc;

    public RequestKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求键查询已受理记录。
     */
    public Optional<Entry> find(String requestId) {
        return jdbc.query(
                "SELECT operation, fingerprint, response_body FROM request_key WHERE request_id = ?",
                (rs, rowNum) -> new Entry(rs.getString("operation"), rs.getString("fingerprint"),
                        rs.getString("response_body")),
                requestId).stream().findFirst();
    }

    /**
     * 写入请求键占位，同事务后续失败会随回滚消失。
     */
    public void insert(String requestId, String operation, String fingerprint, Instant createdAt) {
        jdbc.update(
                "INSERT INTO request_key (request_id, operation, fingerprint, created_at) VALUES (?, ?, ?, ?)",
                requestId, operation, fingerprint, Times.toTimestamp(createdAt));
    }

    /**
     * 业务成功后写入响应快照，供同参重放。
     */
    public void updateResponse(String requestId, String responseBody) {
        jdbc.update("UPDATE request_key SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }
}