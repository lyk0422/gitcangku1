package com.example.starter.support;

import com.example.starter.repo.IdempotentDao;
import com.example.starter.repo.IdempotentDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 写操作幂等执行器：在单个事务内先锁单行仓库版本表互斥并发写，
 * 再执行业务变更并写入幂等成功记录，原子提交；业务失败整体回滚，不占用请求键。
 *
 * <p>同键同参重放首次完整响应，异参（含异操作）返回 409。
 */
@Component
public class IdempotentExecutor {

    private final IdempotentDao idempotentDao;
    private final RepositoryDao repositoryDao;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 当前写事务使用的请求键，供同事务内的记录写入引用。
     */
    private final ThreadLocal<String> currentRequestId = new ThreadLocal<>();

    public IdempotentExecutor(IdempotentDao idempotentDao,
                              RepositoryDao repositoryDao,
                              TransactionTemplate transactionTemplate,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.idempotentDao = idempotentDao;
        this.repositoryDao = repositoryDao;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 当前写事务的请求键；非写事务上下文返回 null。 */
    public String currentRequestId() {
        return currentRequestId.get();
    }

    /**
     * 指纹可在入参阶段确定的写操作：同键同参重放，异参 409，失败不占键。
     */
    public <T> T execute(String requestId, String operation, String requestHash,
                         int httpStatus, Supplier<T> action, Class<T> responseType) {
        // 快速路径：已提交的成功记录直接重放。
        T replay = replayIfPresent(requestId, operation, requestHash, responseType);
        if (replay != null) {
            return replay;
        }

        try {
            return transactionTemplate.execute(status -> {
                // 锁单行仓库版本，串行化全部写事务，保证快照与版本号一致。
                repositoryDao.lockRepositoryState();
                // 等待行锁期间可能已有同键事务提交，再次检查。
                T existing = replayIfPresent(requestId, operation, requestHash, responseType);
                if (existing != null) {
                    return existing;
                }
                idempotentDao.insertPendingIdempotentRequest(
                        requestId, operation, requestHash, Instant.now(clock));
                currentRequestId.set(requestId);
                try {
                    T result = action.get();
                    idempotentDao.completeIdempotentRequest(
                            requestId, httpStatus, writeJson(result));
                    return result;
                } finally {
                    currentRequestId.remove();
                }
            });
        } catch (DuplicateKeyException e) {
            // 同键并发：赢家已提交则重放其结果，否则报告冲突。
            T replayAfterRace = replayIfPresent(requestId, operation, requestHash, responseType);
            if (replayAfterRace != null) {
                return replayAfterRace;
            }
            throw ApiException.conflict("相同请求键的请求正在处理中: " + requestId);
        }
    }

    /**
     * 指纹依赖库内数据、须在持锁事务内计算的写操作（如批量发布）。
     *
     * <p>流程：持行锁后在一致性视图上计算指纹；已有成功记录则同指纹重放、
     * 异指纹 409；否则占位、执行业务、同事务完成记录。业务失败整体回滚不占键。
     */
    public <T> T executeWithLateHash(String requestId, String operation,
                                     Supplier<String> hashSupplier, int httpStatus,
                                     Supplier<T> action, Class<T> responseType) {
        try {
            return transactionTemplate.execute(status -> {
                repositoryDao.lockRepositoryState();
                String requestHash = hashSupplier.get();
                IdempotentRecord record = idempotentDao.findIdempotentRequest(requestId);
                if (record != null) {
                    if (!record.operation().equals(operation)
                            || !record.requestHash().equals(requestHash)) {
                        throw ApiException.conflict(
                                "请求键已用于不同参数的请求: " + requestId);
                    }
                    return readJson(record.responseJson(), responseType);
                }
                idempotentDao.insertPendingIdempotentRequest(
                        requestId, operation, requestHash, Instant.now(clock));
                currentRequestId.set(requestId);
                try {
                    T result = action.get();
                    idempotentDao.completeIdempotentRequest(
                            requestId, httpStatus, writeJson(result));
                    return result;
                } finally {
                    currentRequestId.remove();
                }
            });
        } catch (DuplicateKeyException e) {
            // 行锁串行化后理论上不会到达；防御性处理：赢家已提交则重放。
            IdempotentRecord record = idempotentDao.findIdempotentRequest(requestId);
            if (record != null && record.operation().equals(operation)) {
                return readJson(record.responseJson(), responseType);
            }
            throw ApiException.conflict("相同请求键的请求正在处理中: " + requestId);
        }
    }

    /**
     * 存在成功记录时：同操作同参返回原结果；异参（含异操作）返回 409。不存在返回 null。
     */
    private <T> T replayIfPresent(String requestId, String operation, String requestHash,
                                  Class<T> responseType) {
        IdempotentRecord record = idempotentDao.findIdempotentRequest(requestId);
        if (record == null) {
            return null;
        }
        if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
            throw ApiException.conflict(
                    "请求键已用于不同参数的请求: " + requestId);
        }
        return readJson(record.responseJson(), responseType);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }
}
