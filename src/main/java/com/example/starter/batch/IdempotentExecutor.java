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
 * 幂等命令执行器：同事务内先查 command_log，命中则按参数指纹返回首次响应快照或 409；
 * 未命中执行业务动作并在同一事务写入快照；仅成功命令占键，失败回滚不占键。
 * 并发同键插入冲突时回滚重试，读取对方已提交的结果。
 */
@Component
public class IdempotentExecutor {

    /**
     * 指纹拼接分隔符（NUL 字符）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "\0";

    private static final int MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;

    public IdempotentExecutor(BatchRepository repo, PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 幂等执行：同类型同键同参重放首次响应；同键异参抛 409；动作失败不写快照。
     */
    public StoredResponse execute(String commandType, String commandKey, String fingerprint,
                                  Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    Optional<StoredResponse> logged = replayIfLogged(commandType, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    repo.insertCommand(new BatchRepository.CommandRow(commandType, commandKey, fingerprint,
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
     * 供行锁获取后重查：并发同键请求在锁等待期间可能已由对方提交，须重放首次结果。
     */
    public Optional<StoredResponse> replayIfLogged(String commandType, String commandKey,
                                                   String fingerprint) {
        var existing = repo.findCommand(commandType, commandKey);
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
     * 业务参数指纹：SHA-256 摘要，用于识别同键改参。
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
