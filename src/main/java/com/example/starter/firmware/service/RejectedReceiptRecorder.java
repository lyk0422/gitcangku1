package com.example.starter.firmware.service;

import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.repo.RejectedReceiptRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

import static org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW;

/**
 * 被拒回执记录器：在独立事务（REQUIRES_NEW）中落记录。
 * 回执被拒后业务事务随 422 回滚（失败不占幂等键），但被拒记录必须保留供查询；
 * request_id 唯一约束保证同键重试不重复落记录。
 */
@Component
public class RejectedReceiptRecorder {

    private final RejectedReceiptRepository repository;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    public RejectedReceiptRecorder(RejectedReceiptRepository repository,
                                   PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * 记录一次被拒回执；同 requestId 重复记录时静默去重（唯一约束兜底）。
     */
    public void record(String requestId, long taskId, long releaseId, String deviceId,
                       ReceiptResult result, String reasonCode) {
        requiresNew.executeWithoutResult(status -> {
            try {
                repository.insert(requestId, taskId, releaseId, deviceId, result, reasonCode,
                        Instant.now(clock).toString());
            } catch (DuplicateKeyException e) {
                // 同 requestId 的重试：记录已存在，不重复写入
            }
        });
    }
}
