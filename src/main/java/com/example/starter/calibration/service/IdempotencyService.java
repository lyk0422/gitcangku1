package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.IdempotencyRecord;
import com.example.starter.calibration.repo.IdempotencyRepository;

/**
 * 幂等控制：requestId 同参重放返回首次结果，异参返回 409；
 * 仅在业务成功提交后记录，失败事务回滚不占键。
 */
@Component
public class IdempotencyService {

    private final IdempotencyRepository repository;

    public IdempotencyService(IdempotencyRepository repository) {
        this.repository = repository;
    }

    /**
     * 查找已占用的幂等键：同参返回首次结果用于重放；异参抛出 409；未占用返回空。
     */
    public Optional<IdempotentResponse> replay(String scope, String requestId, String fingerprint) {
        Optional<IdempotencyRecord> existing = repository.find(scope, requestId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecord record = existing.get();
        if (!record.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已被不同参数的请求占用: " + requestId);
        }
        return Optional.of(new IdempotentResponse(record.responseStatus(), record.responseBody()));
    }

    /**
     * 记录首次成功结果。并发下同键已被先提交者占用时，按先提交者结果重放或报 409。
     */
    public IdempotentResponse record(String scope, String requestId, String fingerprint,
                                     int status, String body) {
        try {
            repository.insert(scope, requestId, fingerprint, status, body, Instant.now());
            return new IdempotentResponse(status, body);
        } catch (DuplicateKeyException ex) {
            return replay(scope, requestId, fingerprint)
                    .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_CONFLICT",
                            "requestId 并发占用且结果不可用: " + requestId));
        }
    }
}
