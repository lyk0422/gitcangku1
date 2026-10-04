package com.example.starter.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等命令执行器：业务写入与去重记录在同一事务原子提交。
 *
 * <p>同键同参重放返回首次完整响应快照；同键异参返回 409；
 * 仅成功（HTTP 状态码小于 400）的命令写入 command_log 占键，失败不占键。
 * 并发同键插入冲突时回滚重试，读取已提交结果，不向调用方泄露唯一键异常。
 */
@Component
public class IdempotentExecutor {

    private static final int MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public IdempotentExecutor(BatchRepository repo,
                              PlatformTransactionManager transactionManager,
                              ObjectMapper objectMapper) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作，成功响应（状态码小于 400）连同去重记录一起提交。
     */
    public StoredResponse execute(String type, String requestId, String fingerprint,
                                  Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var logged = loggedResponse(type, requestId, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    if (response.status() < 400) {
                        repo.insertCommand(new BatchRepository.CommandRow(type, requestId, fingerprint,
                                response.status(), response.body()), java.time.Instant.now().toString());
                    }
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同键/同唯一约束冲突：回滚后重试，读取对方已提交的命令快照或最新业务状态
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + requestId);
    }

    /**
     * 查询命令快照：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     */
    public Optional<StoredResponse> loggedResponse(String type, String requestId,
                                                   String fingerprint) {
        var existing = repo.findCommand(type, requestId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId/commandKey 已以不同参数使用: " + requestId);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    /**
     * 结构化指纹：将命令身份（操作、操作者、资源、全部参数与期望版本）按固定键序
     * 序列化为 JSON 后取 SHA-256，不做字符串拼接，避免分隔符碰撞。
     */
    public String structuredFingerprint(Map<String, ?> identity) {
        final String canonical;
        try {
            canonical = objectMapper.writeValueAsString(identity);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("命令身份序列化失败", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}