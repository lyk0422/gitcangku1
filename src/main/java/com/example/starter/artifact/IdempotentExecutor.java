package com.example.starter.artifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

import com.example.starter.artifact.repository.MetaRepository;
import com.example.starter.artifact.repository.RequestLogRepository;
import com.example.starter.artifact.repository.RequestLogRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等写操作执行器。
 *
 * <p>流程：先对仓库元信息单行加排他锁（序列化全部写事务），再查幂等日志：
 * 同键同参直接重放原成功响应；同键异参返回 409；否则执行写操作，
 * 成功后把响应与业务变更在同一事务内原子提交。业务失败抛异常回滚，不占键。
 */
@Component
public class IdempotentExecutor {

    private final MetaRepository metaRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    public IdempotentExecutor(MetaRepository metaRepository,
                              RequestLogRepository requestLogRepository,
                              ObjectMapper objectMapper) {
        this.metaRepository = metaRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 在单个事务内执行幂等写操作。
     *
     * @param requestId     全局唯一请求 id
     * @param requestType   请求类型（register/retract/lock）
     * @param payload       请求参数（用于同键异参检测）
     * @param successStatus 成功时的 HTTP 状态
     * @param action        业务写操作，返回响应体对象；抛 {@link ApiException} 则整体回滚
     * @return 成功响应或重放的原成功响应
     */
    @Transactional
    public ResponseEntity<String> execute(String requestId, String requestType, Object payload,
                                          HttpStatus successStatus, Supplier<Object> action) {
        metaRepository.lockForUpdate();
        String payloadHash = sha256(toJson(payload));
        Optional<RequestLogRow> existing = requestLogRepository.find(requestId);
        if (existing.isPresent()) {
            RequestLogRow row = existing.get();
            if (!row.requestType().equals(requestType) || !row.payloadHash().equals(payloadHash)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "requestId 已被不同参数的请求使用: " + requestId);
            }
            return ResponseEntity.status(row.responseStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(row.responseBody());
        }
        Object result = action.get();
        String body = toJson(result);
        requestLogRepository.insert(new RequestLogRow(
                requestId, requestType, payloadHash, successStatus.value(), body));
        return ResponseEntity.status(successStatus)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
