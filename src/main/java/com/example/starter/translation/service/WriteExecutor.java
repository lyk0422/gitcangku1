package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.RequestLogRow;
import com.example.starter.translation.repo.TranslationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 写操作幂等执行器。
 * 同一事务内完成：requestId 查重 → 业务变更 → 成功结果落 request_log，原子提交；
 * 失败抛异常回滚，requestId 不占键。同键同参重放原成功结果，同键异参返回 409。
 * 并发下同键请求由主键约束兜底：后到的插入冲突回滚后重读记录并重放；
 * 后到请求也可能因对方先提交而触发业务版本冲突（409），此时若对方已落成功记录，
 * 同样按同参重放、异参 409 处理。
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
        } catch (ApiException e) {
            if (e.status() == HttpStatus.CONFLICT) {
                return replayIfCommitted(requestId, requestHash, e);
            }
            throw e;
        }
    }

    /** 并发下对方已提交同键成功记录时重放；同键异参或未落记录时维持原冲突。 */
    private WriteResult replayIfCommitted(String requestId, String requestHash, ApiException conflict) {
        Optional<RequestLogRow> committed = repository.findRequestLog(requestId);
        if (committed.isEmpty()) {
            throw conflict;
        }
        RequestLogRow log = committed.get();
        if (!log.requestHash().equals(requestHash)) {
            throw ApiException.conflict("requestId 已使用且请求参数不同: " + requestId);
        }
        return new WriteResult(log.responseStatus(), log.responseBody());
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
            WriteResult result = action.get();
            try {
                repository.insertRequestLog(requestId, requestHash, result.status(), result.body());
            } catch (DuplicateKeyException e) {
                throw new WriteResult.ReplaySignal(requestId, e);
            }
            return result;
        }
    }
}
