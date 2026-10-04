package com.example.starter.batch;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等写入执行器：同事务内先查 command_log，命中则按指纹返回快照或 409；
 * 未命中执行业务动作并在同一事务写入快照，业务与去重原子提交，失败不占键。
 * 并发同键插入冲突时回滚重试，读取对方已提交结果，不泄露唯一键异常。
 */
@Component
public class IdempotentExecutor {

    private static final int MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;

    public IdempotentExecutor(BatchRepository repo, PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 执行幂等命令：同键同指纹重放首次完整响应，同键异指纹 409。
     */
    public StoredResponse execute(String type, String requestId, String fingerprint,
                                  Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    Optional<StoredResponse> logged = loggedResponse(type, requestId, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    repo.insertCommand(new BatchRepository.CommandRow(type, requestId, fingerprint,
                            response.status(), response.body()), Instant.now().toString());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同事务键冲突：回滚后重试，读取对方已提交的命令快照或业务结果
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
            throw ApiException.conflict("requestId 已以不同参数使用: " + requestId);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }
}