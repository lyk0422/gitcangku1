package com.example.starter.baggage.service;

import com.example.starter.baggage.error.IdempotencyConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Supplier;

/**
 * 写操作幂等去重：requestId 全局唯一。
 * 同键同参重放原成功结果；同键异参返回 409；业务失败回滚、不占键；
 * 业务变更与去重记录在同一事务内原子提交。
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, TransactionTemplate txTemplate, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.txTemplate = txTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 在事务内执行业务动作并记录去重结果。
     *
     * @param requestId   全局唯一请求标识
     * @param fingerprint 请求参数指纹（规范化 JSON）
     * @param resultType  结果类型（用于重放反序列化）
     * @param action      业务动作，抛出运行时异常则整体回滚
     * @return 业务结果或重放的历史结果
     */
    public <T> T execute(String requestId, String fingerprint, Class<T> resultType, Supplier<T> action) {
        StoredResponse existing = lookup(requestId);
        if (existing != null) {
            return replay(existing, fingerprint, resultType);
        }
        try {
            return txTemplate.execute(status -> {
                T result = action.get();
                insert(requestId, fingerprint, writeJson(result));
                return result;
            });
        } catch (RuntimeException ex) {
            // 并发同键请求已提交（本事务因键冲突或并发状态变更失败而回滚）：重读并重放/报冲突；
            // 无已提交记录则原样抛出，失败不占键
            StoredResponse committed = lookup(requestId);
            if (committed != null) {
                return replay(committed, fingerprint, resultType);
            }
            throw ex;
        }
    }

    private StoredResponse lookup(String requestId) {
        List<StoredResponse> rows = jdbc.query(
                "SELECT fingerprint, body FROM request_log WHERE request_id = ?",
                (rs, i) -> new StoredResponse(rs.getString("fingerprint"), rs.getString("body")),
                requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private <T> T replay(StoredResponse stored, String fingerprint, Class<T> resultType) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException("requestId 已被不同参数的请求占用");
        }
        try {
            return objectMapper.readValue(stored.body(), resultType);
        } catch (Exception e) {
            throw new IllegalStateException("幂等记录反序列化失败", e);
        }
    }

    private void insert(String requestId, String fingerprint, String body) {
        try {
            jdbc.update("INSERT INTO request_log (request_id, fingerprint, body) VALUES (?, ?, ?)",
                    requestId, fingerprint, body);
        } catch (DuplicateKeyException e) {
            throw new DuplicateRequestException(e);
        }
    }

    private String writeJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private record StoredResponse(String fingerprint, String body) {
    }

    /** 同键并发插入冲突：触发当前事务回滚后由外层重放。 */
    private static final class DuplicateRequestException extends RuntimeException {
        DuplicateRequestException(Throwable cause) {
            super(cause);
        }
    }
}
