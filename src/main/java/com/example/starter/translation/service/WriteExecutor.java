package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.RequestLogRow;
import com.example.starter.translation.repo.TranslationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 写操作幂等执行器。
 * 同一事务内完成：requestId 查重 → 业务变更 → 成功结果落 request_log，原子提交；
 * 失败抛异常回滚，requestId 不占键。同键同参重放原成功结果，同键异参返回 409。
 * 并发下同键请求由主键约束兜底：后到的插入冲突回滚后重读记录并重放。
 */
@Component
public class WriteExecutor {

    private final TranslationRepository repository;
    private final TransactionalWrite transactionalWrite;

    public WriteExecutor(TranslationRepository repository, TransactionalWrite transactionalWrite) {
        this.repository = repository;
        this.transactionalWrite = transactionalWrite;
    }

    /** 执行幂等写操作：requestId 全局唯一，hash 为规范化请求参数摘要。 */
    public WriteResult execute(String requestId, String requestHash, Supplier<WriteResult> action) {
        try {
            return transactionalWrite.run(requestId, requestHash, action);
        } catch (WriteResult.ReplaySignal signal) {
            return replay(requestId, requestHash);
        }
    }

    private WriteResult replay(String requestId, String requestHash) {
        RequestLogRow log = repository.findRequestLog(requestId)
                .orElseThrow(() -> ApiException.conflict("requestId 冲突且未找到已提交记录: " + requestId));
        if (!log.requestHash().equals(requestHash)) {
            throw ApiException.conflict("requestId 已使用且请求参数不同: " + requestId);
        }
        return new WriteResult(log.responseStatus(), log.responseBody());
    }

    /**
     * 事务内执行：查重、业务变更与去重记录同一事务提交。
     * 独立 Spring Bean 以保证 @Transactional 代理生效。
     */
    @Component
    public static class TransactionalWrite {

        private final TranslationRepository repository;

        public TransactionalWrite(TranslationRepository repository) {
            this.repository = repository;
        }

        @Transactional
        public WriteResult run(String requestId, String requestHash, Supplier<WriteResult> action) {
            Optional<RequestLogRow> existing = repository.findRequestLog(requestId);
            if (existing.isPresent()) {
                RequestLogRow log = existing.get();
                if (!log.requestHash().equals(requestHash)) {
                    throw ApiException.conflict("requestId 已使用且请求参数不同: " + requestId);
                }
                return new WriteResult(log.responseStatus(), log.responseBody());
            }
            WriteResult result;
            try {
                result = action.get();
            } catch (ApiException ex) {
                // 业务动作可能在文档行锁上排队后，因先提交的同键写操作而失败（如期望版本不符）；
                // 若同 requestId 的成功记录此刻已提交，则为重放而非冲突，回滚本事务后重放原结果。
                Optional<RequestLogRow> concurrent = repository.findRequestLog(requestId);
                if (concurrent.isPresent()) {
                    if (concurrent.get().requestHash().equals(requestHash)) {
                        throw new WriteResult.ReplaySignal(requestId, ex);
                    }
                    throw ApiException.conflict("requestId 已使用且请求参数不同: " + requestId);
                }
                throw ex;
            }
            try {
                repository.insertRequestLog(requestId, requestHash, result.status(), result.body());
            } catch (DuplicateKeyException e) {
                throw new WriteResult.ReplaySignal(requestId, e);
            }
            return result;
        }
    }
}
