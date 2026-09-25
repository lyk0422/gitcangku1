package com.example.starter.observation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 幂等去重支撑：基于 request_log 的同键同参重放、同键异参 409、失败不占键。
 *
 * <p>写操作在事务内先占位写入去重记录，业务成功后回填响应并同事务提交；
 * 任何业务失败都会回滚，去重记录不占键。供各业务服务复用。
 */
@Component
public class IdempotencyStore {

    private static final String SEPARATOR = "";

    private final RequestLogRepository requestLogRepository;

    public IdempotencyStore(RequestLogRepository requestLogRepository) {
        this.requestLogRepository = requestLogRepository;
    }

    /**
     * 已落库的成功结果：HTTP 状态码与响应体 JSON 原文。
     */
    public record StoredResult(int status, String bodyJson) {
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参抛 409；无记录返回 null 继续执行。
     */
    public StoredResult findReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new StoredResult(entry.responseStatus(), entry.responseBody());
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    public StoredResult insertPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new StoredResult(entry.responseStatus(), entry.responseBody());
        }
    }

    /**
     * 业务成功后回填去重记录响应，与业务变更同事务提交。
     */
    public void complete(String requestId, int status, String bodyJson) {
        requestLogRepository.complete(requestId, status, bodyJson);
    }

    /**
     * 计算请求操作与参数的指纹，同键异参时判定 409。
     */
    public static String fingerprint(String operation, String... parts) {
        StringBuilder raw = new StringBuilder(operation);
        for (String part : parts) {
            raw.append(SEPARATOR).append(part == null ? "<null>" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
