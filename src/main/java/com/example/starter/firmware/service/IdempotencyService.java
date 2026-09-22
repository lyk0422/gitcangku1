package com.example.starter.firmware.service;

import com.example.starter.firmware.error.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 幂等去重组件：所有写操作携带全局唯一 requestId。
 * 同键同参重放首次成功结果；同键异参返回 409；业务失败时随事务回滚，不占键。
 * 键记录与业务变更在同一事务内提交，保证原子性。
 */
@Component
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 在调用方事务内执行幂等写操作。
     *
     * @param requestId 全局唯一请求号
     * @param endpoint  写操作标识
     * @param paramHash 业务参数（不含 requestId）的散列
     * @param type      响应类型
     * @param action    业务动作，仅在首次请求时执行
     * @return 首次执行结果或重放的首次成功结果
     */
    public <T> T execute(String requestId, String endpoint, String paramHash, Class<T> type, Supplier<T> action) {
        try {
            jdbc.update("INSERT INTO idempotency_key(request_id, endpoint, request_hash) VALUES (?,?,?)",
                    requestId, endpoint, paramHash);
        } catch (DuplicateKeyException e) {
            return replay(requestId, endpoint, paramHash, type);
        }
        T result = action.get();
        jdbc.update("UPDATE idempotency_key SET response_body=? WHERE request_id=?",
                toJson(result), requestId);
        return result;
    }

    private <T> T replay(String requestId, String endpoint, String paramHash, Class<T> type) {
        var row = jdbc.queryForMap(
                "SELECT endpoint, request_hash, response_body FROM idempotency_key WHERE request_id=?", requestId);
        if (!endpoint.equals(row.get("endpoint")) || !paramHash.equals(row.get("request_hash"))) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId already used with different parameters");
        }
        Object body = row.get("response_body");
        if (body == null) {
            // 并发场景下首请求尚未提交完成，按冲突处理，客户端可重试。
            throw ApiException.conflict("REQUEST_IN_FLIGHT", "request is still in flight, retry later");
        }
        try {
            return objectMapper.readValue(body.toString(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupted idempotency snapshot for " + requestId, e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize idempotent response", e);
        }
    }
}
