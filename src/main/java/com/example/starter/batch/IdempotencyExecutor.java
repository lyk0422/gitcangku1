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
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 新增写入的幂等执行器：requestId + 结构化身份指纹。
 *
 * <p>身份为有序结构（操作、操作者、资源、全部参数、期望版本），序列化为 JSON 后取
 * SHA-256，避免分隔符拼接碰撞。同键同参重放首次完整响应快照，同键异参返回 409；
 * 仅成功命令写入 command_log，失败不占键；业务写入与去重记录在同一事务原子提交，
 * 并发同键冲突时回滚重试，不向调用方泄露唯一键异常。
 */
@Component
public class IdempotencyExecutor {

    private static final int MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public IdempotencyExecutor(BatchRepository repo,
                               PlatformTransactionManager transactionManager,
                               ObjectMapper objectMapper) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时回滚重试，读取已提交结果。
     */
    public StoredResponse execute(String commandType, String requestId,
                                  Map<String, Object> identity, Supplier<StoredResponse> action) {
        String fingerprint = fingerprint(identity);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var existing = repo.findCommand(commandType, requestId);
                    if (existing.isPresent()) {
                        BatchRepository.CommandRow row = existing.get();
                        if (!row.fingerprint().equals(fingerprint)) {
                            throw ApiException.conflict("requestId 已以不同参数使用: " + requestId);
                        }
                        return new StoredResponse(row.responseStatus(), row.responseBody());
                    }
                    StoredResponse response = action.get();
                    repo.insertCommand(new BatchRepository.CommandRow(commandType, requestId,
                            fingerprint, response.status(), response.body()), Instant.now().toString());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同键或唯一约束冲突：回滚后重试，读取对方已提交的快照或由预检查给出业务冲突
            }
        }
        throw ApiException.conflict("请求并发冲突，请重试: " + requestId);
    }

    /**
     * 结构化身份指纹：LinkedHashMap 键序固定，JSON 序列化结果确定，取 SHA-256 十六进制。
     */
    private String fingerprint(Map<String, Object> identity) {
        String canonical;
        try {
            canonical = objectMapper.writeValueAsString(identity);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("身份序列化失败", e);
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