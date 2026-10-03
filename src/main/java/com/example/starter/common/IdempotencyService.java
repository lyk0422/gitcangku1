package com.example.starter.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 幂等执行器：requestId 与业务写入同事务落库。
 * 同参重放返回首次响应；异参重放返回 409；失败事务回滚，不消耗请求键。
 */
@Service
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public IdempotencyService(JdbcClient jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 以 requestId 为幂等键执行动作。
     *
     * @param requestId 请求ID（幂等键）
     * @param operation 操作类型（COLLECT/SEAL/HANDOVER/ANOMALY/RESEAL）
     * @param params    参与一致性校验的请求参数（序列化后做 SHA-256）
     * @param action    业务动作，仅在首次请求时执行
     * @return 首次执行或重放的响应
     */
    public StoredResponse execute(String requestId, String operation, Object params, Supplier<ApiResult> action) {
        String hash = sha256(toJson(params));
        try {
            jdbc.sql("INSERT INTO request_log(request_id, operation, params_hash, response_code, response_body, created_at)"
                    + " VALUES (?,?,?,0,'',?)")
                    .params(requestId, operation, hash, Timestamp.from(clock.instant()))
                    .update();
        } catch (DuplicateKeyException e) {
            return replay(requestId, hash);
        }
        ApiResult result = action.get();
        String body = toJson(result.payload());
        jdbc.sql("UPDATE request_log SET response_code=?, response_body=? WHERE request_id=?")
                .params(result.status(), body, requestId)
                .update();
        return new StoredResponse(result.status(), body);
    }

    private StoredResponse replay(String requestId, String hash) {
        record Row(String paramsHash, int responseCode, String responseBody) {
        }
        Row row = jdbc.sql("SELECT params_hash, response_code, response_body FROM request_log WHERE request_id=?")
                .param(requestId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getInt(2), rs.getString(3)))
                .single();
        if (!row.paramsHash().equals(hash)) {
            throw BizException.conflict("requestId 已使用且参数不一致: " + requestId);
        }
        return new StoredResponse(row.responseCode(), row.responseBody());
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}