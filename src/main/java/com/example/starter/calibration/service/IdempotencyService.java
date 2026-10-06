package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.IdempotencyRecord;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等写入支持：身份为操作+操作者+资源+全部参数及期望版本的结构化 JSON（避免分隔符碰撞）。
 * 同键同参重放首次完整响应；同键异参 409；业务失败随事务回滚不占键；
 * 占位记录与业务写入同事务提交，并发同键不泄露唯一键异常。
 * 所有方法须在调用方的事务内执行。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository records;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository records, ObjectMapper objectMapper) {
        this.records = records;
        this.objectMapper = objectMapper;
    }

    /**
     * 在调用方事务内执行幂等写入：先查重放/异参，再占位，执行业务并写回响应。
     *
     * @param requestId 幂等请求 ID
     * @param operation 操作类型
     * @param identity  结构化身份（将序列化为 JSON 比较）
     * @param type      响应类型
     * @param action    业务动作，返回响应对象
     * @return 业务响应或首次存储的重放响应
     */
    public <T> T execute(String requestId, String operation, Object identity, Class<T> type, Supplier<T> action) {
        String identityJson = toJson(identity);
        Optional<IdempotencyRecord> existing = records.find(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, identityJson, type);
        }
        try {
            records.insert(requestId, operation, identityJson, Instant.now());
        } catch (DuplicateKeyException ex) {
            // 并发同键：唯一约束串行化后读取已提交记录，重放或报异参冲突，不泄露唯一键异常
            IdempotencyRecord committed = records.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("REQUEST_IN_FLIGHT",
                            "相同 requestId 的请求正在处理中，请重试: " + requestId));
            return replay(committed, operation, identityJson, type);
        }
        T result = action.get();
        records.storeResponse(requestId, toJson(result));
        return result;
    }

    private <T> T replay(IdempotencyRecord record, String operation, String identityJson, Class<T> type) {
        if (!record.operation().equals(operation) || !record.identityJson().equals(identityJson)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已用于不同参数的请求: " + record.requestId());
        }
        if (record.responseJson() == null) {
            throw ApiException.conflict("REQUEST_IN_FLIGHT",
                    "相同 requestId 的请求正在处理中，请重试: " + record.requestId());
        }
        try {
            return objectMapper.readValue(record.responseJson(), type);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等响应反序列化失败: " + record.requestId(), ex);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等身份序列化失败", ex);
        }
    }
}
