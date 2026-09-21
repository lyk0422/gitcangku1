package com.example.starter.plan.repo;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 写操作幂等记录持久化。仅缓存成功结果；同一操作类型内 requestKey 唯一。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 幂等记录。
     *
     * @param requestHash  请求参数规范化后的 SHA-256
     * @param responseJson 首次成功响应快照
     */
    public record Record(String requestHash, String responseJson) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按操作类型与幂等键查询记录。
     */
    public Optional<Record> find(String opType, String requestKey) {
        return jdbc.query("SELECT request_hash, response_json FROM idempotency_record"
                        + " WHERE op_type = ? AND request_key = ?",
                (rs, n) -> new Record(rs.getString("request_hash"), rs.getString("response_json")),
                opType, requestKey).stream().findFirst();
    }

    /**
     * 写入幂等记录；同键冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insert(String opType, String requestKey, String requestHash, String responseJson,
                       long nowMillis) {
        jdbc.update("INSERT INTO idempotency_record (op_type, request_key, request_hash, response_json, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                opType, requestKey, requestHash, responseJson, nowMillis);
    }
}
