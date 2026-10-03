package com.example.starter.custody;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 幂等请求键:同一requestId同参重放首次成功结果,异参返回409;
 * 记录与业务写入同事务,失败回滚不残留请求键。
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
     * 在调用方事务内执行幂等操作。
     *
     * @param requestId 请求键
     * @param operation 操作类型(SPLIT或SIGNOFF)
     * @param params    参与比对的规范化参数串
     * @param type      结果类型
     * @param action    首次执行的业务动作
     * @return 首次执行结果或重放的历史结果
     */
    public <T> T execute(String requestId, String operation, String params,
                         Class<T> type, Supplier<T> action) {
        String hash = sha256(params);
        List<String[]> existing = jdbc.query(
                "SELECT params_hash, response_json FROM idempotency_key WHERE request_id = ?",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
                requestId);
        if (!existing.isEmpty()) {
            String[] row = existing.get(0);
            if (!row[0].equals(hash)) {
                throw ApiException.conflict("requestId已使用且参数不一致: " + requestId);
            }
            try {
                return objectMapper.readValue(row[1], type);
            } catch (Exception e) {
                throw new IllegalStateException("幂等结果反序列化失败", e);
            }
        }
        T result = action.get();
        String json;
        try {
            json = objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果序列化失败", e);
        }
        try {
            jdbc.update("INSERT INTO idempotency_key (request_id, operation, params_hash, response_json)"
                    + " VALUES (?, ?, ?, ?)", requestId, operation, hash, json);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("requestId并发冲突: " + requestId);
        }
        return result;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}