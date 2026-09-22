package com.example.starter.service.impl;

import com.example.starter.domain.ApiException;
import com.example.starter.repository.IdempotencyDao;
import com.example.starter.repository.RepositoryVersionDao;
import com.example.starter.service.IdempotencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 写操作幂等执行器。
 *
 * <p>所有写操作在同一事务内先锁定仓库版本单行，使登记、撤回、锁定全局串行；
 * 持锁后查询幂等请求记录：同键同参重放原成功结果，同键异参返回409；首次请求执行业务，
 * 成功后写幂等记录并与业务变更原子提交，业务失败抛异常整体回滚、不占键。</p>
 */
@Service
public class IdempotencyServiceImpl implements IdempotencyService {

    private final IdempotencyDao idempotencyDao;
    private final RepositoryVersionDao repositoryVersionDao;
    private final ObjectMapper objectMapper;

    public IdempotencyServiceImpl(IdempotencyDao idempotencyDao,
                                  RepositoryVersionDao repositoryVersionDao,
                                  ObjectMapper objectMapper) {
        this.idempotencyDao = idempotencyDao;
        this.repositoryVersionDao = repositoryVersionDao;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public <T> WriteOutcome<T> execute(String requestId, String operation, String fingerprint,
                                       Class<T> bodyType, Supplier<WriteOutcome<T>> action) {
        // 全局串行点：登记/撤回/锁定在此锁上排队，保证看到一致的仓库状态
        repositoryVersionDao.lockAndGet();

        Optional<IdempotencyDao.RecordRow> existing = idempotencyDao.find(requestId);
        if (existing.isPresent()) {
            IdempotencyDao.RecordRow row = existing.get();
            if (!row.operation().equals(operation) || !row.fingerprint().equals(fingerprint)) {
                throw new ApiException(409, "IDEMPOTENCY_PARAM_MISMATCH",
                        "requestId 已用于参数不同的写请求");
            }
            return new WriteOutcome<>(row.statusCode(), fromJson(row.responseBody(), bodyType), true);
        }

        WriteOutcome<T> outcome = action.get();
        if (outcome.statusCode() >= 200 && outcome.statusCode() < 300) {
            boolean inserted = idempotencyDao.tryInsert(requestId, operation, fingerprint,
                    outcome.statusCode(), toJson(outcome.body()));
            if (!inserted) {
                // 全局行锁已串行化写操作，正常不会走到这里；防御性处理为冲突
                throw new ApiException(409, "IDEMPOTENCY_KEY_CONFLICT",
                        "requestId 并发冲突，请使用原参数重放");
            }
        }
        return outcome;
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ApiException(500, "RESPONSE_SERIALIZATION_FAILED", "响应序列化失败");
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new ApiException(500, "RESPONSE_DESERIALIZATION_FAILED", "历史响应反序列化失败");
        }
    }
}
