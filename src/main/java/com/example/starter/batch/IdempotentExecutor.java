package com.example.starter.batch;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等命令执行器：基于 command_log 主键 + 参数指纹。
 * 同键同参重放返回首次响应快照，同键改参返回 409；仅成功命令写入 command_log（失败不占键），
 * 业务写入与去重记录同事务原子提交；并发同键插入冲突时回滚重试，读取已提交结果，不暴露唯一键错误。
 */
@Component
public class IdempotentExecutor {

    private static final int MAX_ATTEMPTS = 3;

    /**
     * 指纹拼接分隔符（NUL 字符）：业务参数不可能包含该字符，避免字段间碰撞。
     */
    private static final String SEP = String.valueOf((char) 0);

    private final BatchRepository repo;
    private final TransactionTemplate tx;

    public IdempotentExecutor(BatchRepository repo, PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时重试，读取已提交结果。
     */
    public StoredResponse execute(String type, String commandKey, String fingerprint,
                                  Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var logged = logged(type, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    repo.insertCommand(new BatchRepository.CommandRow(type, commandKey, fingerprint,
                            response.status(), response.body()), Instant.now().toString());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同事务键冲突：回滚后重试，读取对方已提交的命令快照或业务结果
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    /**
     * 查询命令快照：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     */
    public Optional<StoredResponse> logged(String type, String commandKey, String fingerprint) {
        var existing = repo.findCommand(type, commandKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("commandKey 已以不同参数使用: " + commandKey);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    /**
     * 结构化指纹：操作、资源与全部参数按序拼接取 SHA-256；文本原样，十进制须先规范化。
     */
    public static String fingerprint(String... parts) {
        String canonical = String.join(SEP, parts);
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
