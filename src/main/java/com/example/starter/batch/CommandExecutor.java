package com.example.starter.batch;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 命令幂等执行器：同事务内先查 command_log，命中且指纹一致返回首次响应快照，
 * 指纹不一致抛 409；未命中执行业务动作并在同一事务写入快照。
 * 并发同键插入冲突时回滚重试，读取对方已提交结果；仅成功命令占用 commandKey。
 */
@Component
public class CommandExecutor {

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository batchRepository;
    private final TransactionTemplate tx;
    private final UtcClock clock;

    public CommandExecutor(BatchRepository batchRepository,
                           PlatformTransactionManager transactionManager,
                           UtcClock clock) {
        this.batchRepository = batchRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 以幂等方式执行命令；action 内的全部写入与 command_log 快照在同一事务提交。
     */
    public StoredResponse execute(String commandType, String commandKey, String fingerprint,
                                  Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var logged = stored(commandType, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    batchRepository.insertCommand(new BatchRepository.CommandRow(commandType, commandKey,
                            fingerprint, response.status(), response.body()), clock.now().toString());
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
     * 供业务动作在行锁等待后复查（对方可能已在锁等待期间提交同键命令）。
     */
    public Optional<StoredResponse> stored(String commandType, String commandKey, String fingerprint) {
        var existing = batchRepository.findCommand(commandType, commandKey);
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
