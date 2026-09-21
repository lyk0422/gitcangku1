package com.example.starter.service;

import com.example.starter.common.ApiException;
import com.example.starter.domain.RequestRecord;
import com.example.starter.repo.DuplicateKeyException;
import com.example.starter.repo.RequestDedupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等执行器：为携带 requestId 的写操作提供去重语义。
 *
 * <p>占位记录、业务写入与结果回填在同一事务中提交；业务失败时占位随事务回滚，
 * 失败请求不占用 requestId。同一 requestId 相同参数重试返回首次结果，
 * 不同参数返回 409。
 */
@Component
public class IdempotencyExecutor {

    private final RequestDedupRepository dedupRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyExecutor(RequestDedupRepository dedupRepository, ObjectMapper objectMapper) {
        this.dedupRepository = dedupRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 以 requestId 为去重键执行写操作。
     *
     * @param operation   操作类型
     * @param requestId   客户端请求 ID，空白抛 400
     * @param fingerprint 请求参数指纹（不含 requestId）
     * @param resultType  结果类型，用于重放时反序列化
     * @param action      业务动作
     */
    public <T> T execute(String operation, String requestId, String fingerprint,
                         Class<T> resultType, Supplier<T> action) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("INVALID_REQUEST_ID", "requestId 不能为空");
        }
        Optional<RequestRecord> existing = tryInsertPlaceholder(operation, requestId, fingerprint);
        if (existing.isPresent()) {
            RequestRecord record = existing.get();
            if (!record.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("REQUEST_ID_CONFLICT",
                        "requestId 已被不同参数的请求使用");
            }
            return readResult(record.resultJson(), resultType);
        }
        try {
            T result = action.get();
            dedupRepository.complete(requestId, writeResult(result));
            return result;
        } catch (RuntimeException e) {
            dedupRepository.abandon(requestId);
            throw e;
        }
    }

    private Optional<RequestRecord> tryInsertPlaceholder(String operation, String requestId,
                                                         String fingerprint) {
        try {
            dedupRepository.insertPlaceholder(requestId, operation, fingerprint);
            return Optional.empty();
        } catch (DuplicateKeyException e) {
            return dedupRepository.find(requestId);
        }
    }

    private String writeResult(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("结果序列化失败", e);
        }
    }

    private <T> T readResult(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("结果反序列化失败", e);
        }
    }
}
