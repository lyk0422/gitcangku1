package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.DedupRecord;
import com.example.starter.calibration.repo.RequestDedupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 写请求幂等去重：requestId 全局唯一。同参数重放首次完整响应，异参数 409；
 * 业务失败则去重记录随事务回滚，requestId 可复用；唯一键竞争时以先提交者的业务结果为准。
 * 去重记录与业务写入在同一事务提交。
 */
@Service
public class RequestDedupService {

    /** 等待并发先提交者结果可见的重试次数（每次间隔 30ms）。 */
    private static final int REPLAY_RETRIES = 100;
    /** 占位竞争的最大轮数（先占位者回滚后可接管）。 */
    private static final int OWNER_RETRIES = 3;
    /** 固定数量的分条锁，串行化同 requestId 的 JVM 内并发请求。 */
    private static final int STRIPES = 256;

    private final RequestDedupRepository repo;
    private final ObjectMapper objectMapper;
    private final Object[] stripes = new Object[STRIPES];

    public RequestDedupService(RequestDedupRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new Object();
        }
    }

    /**
     * 在幂等去重保护下执行写业务。requestId 为空时直接执行（兼容旧调用）。
     * 提供 requestId 时操作者必须非空；身份哈希覆盖操作、资源、操作者、期望版本与全部参数。
     */
    public <T> T execute(String requestId, String operation, String resource, String operator,
                         String expectedVersion, List<String> params,
                         Class<T> responseType, Supplier<T> business) {
        if (requestId == null || requestId.isBlank()) {
            return business.get();
        }
        String rid = requestId.trim();
        if (operator == null || operator.isBlank()) {
            throw ApiException.badRequest("使用 requestId 时操作者不能为空");
        }
        String identity = IdentityHash.of(operation, resource, operator.trim(), expectedVersion, params);
        synchronized (stripe(rid)) {
            for (int attempt = 0; attempt < OWNER_RETRIES; attempt++) {
                var existing = repo.findById(rid);
                if (existing.isPresent()) {
                    return replay(existing.get(), identity, responseType);
                }
                try {
                    repo.insertPlaceholder(rid, identity, operation, resource, operator.trim(), Instant.now());
                } catch (DuplicateKeyException ex) {
                    // 并发竞争者已占位：等待其提交的业务结果；其回滚则下一轮接管
                    Optional<DedupRecord> winner = awaitRecord(rid);
                    if (winner.isPresent()) {
                        return replay(winner.get(), identity, responseType);
                    }
                    continue;
                }
                T result = business.get();
                repo.fillResponse(rid, writeJson(result));
                return result;
            }
        }
        throw ApiException.conflict("REQUEST_ID_CONFLICT", "requestId 竞争未决: " + rid);
    }

    private Object stripe(String rid) {
        return stripes[(rid.hashCode() & 0x7fffffff) % STRIPES];
    }

    /**
     * 唯一键竞争后等待先提交者的去重记录可见（有界重试，避免长时间阻塞）；
     * 先占位者回滚导致记录不可见时返回空。
     */
    private Optional<DedupRecord> awaitRecord(String rid) {
        for (int i = 0; i < REPLAY_RETRIES; i++) {
            var found = repo.findById(rid);
            if (found.isPresent() && found.get().responseBody() != null) {
                return found;
            }
            try {
                Thread.sleep(30L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Optional.empty();
    }

    private <T> T replay(DedupRecord record, String identity, Class<T> responseType) {
        if (!record.identityHash().equals(identity)) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT", "requestId 已用于不同请求: " + record.requestId());
        }
        if (record.responseBody() == null) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT", "requestId 首次请求未完成: " + record.requestId());
        }
        try {
            return objectMapper.readValue(record.responseBody(), responseType);
        } catch (Exception ex) {
            throw new IllegalStateException("去重响应反序列化失败", ex);
        }
    }

    private String writeJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }
}
