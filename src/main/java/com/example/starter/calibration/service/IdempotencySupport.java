package com.example.starter.calibration.service;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.IdempotentRequest;
import com.example.starter.calibration.repo.IdempotentRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 写接口幂等支撑：requestId 同参重放、异参 409、失败不占键。
 * 占键通过插入占位行完成，业务成功后在同事务补写首次完整响应快照；
 * 业务失败随外层事务回滚，占位行一并消失。同 requestId 并发时，
 * 唯一索引让第二个事务阻塞至首个事务提交或回滚后再裁决，因此不会读到占位中间态。
 */
@Component
public class IdempotencySupport {

    /** 并发同 requestId 时等待首个事务落定的最长时间（受数据库 LOCK_TIMEOUT 与本上限双重约束）。 */
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(20);
    private static final long WAIT_STEP_MILLIS = 20;

    private final IdempotentRequestRepository requests;
    private final ObjectMapper objectMapper;

    public IdempotencySupport(IdempotentRequestRepository requests, ObjectMapper objectMapper) {
        this.requests = requests;
        this.objectMapper = objectMapper;
    }

    /**
     * 占用幂等键。requestId 为空表示不启用幂等，返回 null 由调用方继续业务；
     * 插入占位成功同样返回 null（当前事务为首提）；发现已成功的同 requestId 记录时：
     * 指纹相同返回该记录（重放），指纹不同抛 409。
     */
    public IdempotentRequest acquire(String requestId, String operator,
                                     String operationType, String fingerprint) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        String id = requestId.trim();
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        if (requests.tryInsertPlaceholder(id, operator, operationType, fingerprint)) {
            return null;
        }
        while (true) {
            // 前序事务持有键锁/已提交：先看是否已有成功记录可裁决
            IdempotentRequest existing = requests.find(id).orElse(null);
            if (existing != null) {
                if (!existing.operationType().equals(operationType)
                        || !existing.operator().equals(operator)
                        || !existing.fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict("IDEMPOTENCY_PARAM_MISMATCH",
                            "requestId 已用于不同参数的请求: " + id);
                }
                return existing;
            }
            // 尚无可见记录：可能前序事务仍在进行（占位未提交），也可能已失败回滚（键已释放）。
            // 重新尝试占位：成功者作为新的首提继续业务，落实“失败不占键”。
            if (requests.tryInsertPlaceholder(id, operator, operationType, fingerprint)) {
                return null;
            }
            if (System.nanoTime() >= deadline) {
                throw ApiException.conflict("IDEMPOTENCY_PENDING",
                        "同 requestId 的前序请求仍在处理中: " + id);
            }
            try {
                Thread.sleep(WAIT_STEP_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw ApiException.conflict("IDEMPOTENCY_INTERRUPTED", "等待幂等前序请求时被中断");
            }
        }
    }

    /**
     * 业务成功后补写首次完整响应快照；未启用幂等时为空操作。
     */
    public void complete(String requestId, int httpStatus, Object responseBody) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        try {
            String snapshot = objectMapper.writeValueAsString(responseBody);
            requests.finalizeSuccess(requestId.trim(), httpStatus, snapshot);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("序列化幂等响应快照失败", ex);
        }
    }

    /**
     * 业务失败时仅需向上抛出原异常：外层事务回滚会连带删除占位行，失败不占键。
     */
    public RuntimeException abort(String requestId, RuntimeException failure) {
        return failure;
    }

    /**
     * 反序列化首次成功响应快照用于重放。
     */
    public <T> IdempotentResult<T> replay(IdempotentRequest existing, Class<T> bodyType) {
        try {
            T body = objectMapper.readValue(existing.responseSnapshot(), bodyType);
            return IdempotentResult.replay(existing.httpStatus(), body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("反序列化幂等响应快照失败", ex);
        }
    }
}
