package com.example.starter.batch;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等命令执行器：同事务内先查 command_log，命中则按指纹返回快照或 409；
 * 未命中执行业务动作并写入快照；失败命令回滚不占键。
 * 并发同键（含唯一约束冲突，如同一标签号）插入冲突时回滚重试，读取已提交结果。
 */
@Component
public class IdempotentExecutor {

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;

    public IdempotentExecutor(BatchRepository repo, PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 幂等执行：同类型同 commandKey 同指纹重放首次响应；同键异参抛 409；
     * 业务异常或唯一约束冲突导致回滚时不写入 command_log（失败不占键）。
     */
    public StoredResponse execute(String type, String commandKey, String fingerprint,
                                  Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var logged = loggedResponse(type, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    repo.insertCommand(new BatchRepository.CommandRow(type, commandKey, fingerprint,
                            response.status(), response.body()), Instant.now().toString());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同事务键冲突（命令键或业务唯一约束，如标签号）：回滚后重试，
                // 读取对方已提交的命令快照或业务结果
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    /**
     * 查询命令快照：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     */
    public Optional<StoredResponse> loggedResponse(String type, String commandKey,
                                                   String fingerprint) {
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
}
