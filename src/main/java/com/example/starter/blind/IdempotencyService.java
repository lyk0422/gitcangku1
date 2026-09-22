package com.example.starter.blind;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 写操作幂等执行器：权限校验由调用方先于本类完成；
 * 同键同参重放原成功结果，同键异参返回 409；业务失败回滚后不占键。
 */
@Service
public class IdempotencyService {

    private final ExperimentRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(ExperimentRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 在单事务内执行：占键（占位行）→ 业务变更 → 写入成功响应，三者原子提交。
     *
     * @param requestId 全局唯一请求编号
     * @param actorId   操作者编号（幂等参数组成部分）
     * @param role      操作者角色（幂等参数组成部分）
     * @param action    操作类型标识
     * @param fingerprint 业务参数摘要原文（不含 requestId）
     * @param responseType 响应类型
     * @param business  业务逻辑，抛出异常则整体回滚且不占键
     */
    @Transactional
    public <T> T execute(String requestId, String actorId, ActorRole role, String action,
                         String fingerprint, Class<T> responseType, Supplier<T> business) {
        String paramsHash = sha256(action + '|' + actorId + '|' + role + '|' + fingerprint);

        var existing = repository.findIdempotency(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), paramsHash, responseType);
        }

        try {
            repository.insertIdempotency(
                    new ExperimentRepository.IdempotencyRow(
                            requestId, actorId, role.name(), action, paramsHash, 0, ""),
                    LocalDateTime.now());
        } catch (DuplicateKeyException dup) {
            // 并发同键：另一事务已提交，读取其结果回放或判异参
            var won = repository.findIdempotency(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId));
            return replay(won, paramsHash, responseType);
        }

        T result = business.get();

        String body;
        try {
            body = objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialize response", ex);
        }
        repository.updateIdempotencyResponse(requestId, 200, body);
        return result;
    }

    private <T> T replay(ExperimentRepository.IdempotencyRow row, String paramsHash,
                         Class<T> responseType) {
        if (!row.paramsHash().equals(paramsHash)) {
            throw ApiException.conflict("requestId reused with different parameters");
        }
        try {
            return objectMapper.readValue(row.responseBody(), responseType);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to replay stored response", ex);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
