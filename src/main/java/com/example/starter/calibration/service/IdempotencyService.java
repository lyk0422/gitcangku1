package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.IdempotentReplayException;
import com.example.starter.calibration.model.IdempotentRequest;
import com.example.starter.calibration.repo.IdempotentRequestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等裁决。在业务事务内先插入 request_id 占位行：唯一索引使同 requestId 的并发请求
 * 在占位行上排队——先提交者胜出，后来者要么拿到重放快照（同指纹），要么 409（异指纹）。
 * 占位行与业务写入同事务：业务失败整体回滚，占位行随之消失，因此“失败不占键”。
 */
@Service
public class IdempotencyService {

    private final IdempotentRequestRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotentRequestRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等闸门。未携带 requestId 时返回禁用闸门，业务照常执行且不记录快照。
     *
     * @param requestId     X-Request-Id（可空）
     * @param scope         操作作用域（如 RELEASE、BUDGET:M-1、REVISION:M-1、CERT_REVOKE:12）
     * @param actor         操作者
     * @param operationType 操作类型
     * @param parameters    规范化后的影响结果字段（集合须已排序）
     */
    public Gate begin(String requestId, String scope, String actor, String operationType,
                      Map<String, ?> parameters) {
        if (requestId == null || requestId.isBlank()) {
            return Gate.disabled();
        }
        String id = requestId.trim();
        String fingerprint = Fingerprints.of(actor, operationType, parameters);

        Optional<IdempotentRequest> existing = repository.find(id);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        // 并发下可能多次撞键：先到者回滚（失败不占键）后占位行消失，需要重试插入；
        // 先到者提交则读取其快照裁决。少量重试即可收敛。
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                // 占位行：status=0、body 为空，事务提交前由 recordSuccess 补全。
                repository.insert(new IdempotentRequest(id, scope, actor, operationType, fingerprint,
                        0, "", Instant.now()));
                return new Gate(true, id, scope, actor, operationType, fingerprint);
            } catch (DuplicateKeyException ex) {
                IdempotentRequest winner = repository.find(id).orElse(null);
                if (winner != null) {
                    return replayOrConflict(winner, fingerprint);
                }
                // winner 已回滚，占位行释放，重试抢占
            }
        }
        throw ApiException.conflict("IDEMPOTENCY_RACE",
                "幂等键并发冲突，请重试: " + id);
    }

    private Gate replayOrConflict(IdempotentRequest saved, String fingerprint) {
        if (!saved.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_PARAM_CONFLICT",
                    "requestId 已用于不同参数的请求: " + saved.requestId());
        }
        throw new IdempotentReplayException(HttpStatus.valueOf(saved.statusCode()), saved.responseBody());
    }

    /**
     * 业务成功后补全快照（与业务同事务提交）。
     */
    public void recordSuccess(Gate gate, int statusCode, Object responseBody) {
        if (!gate.enabled()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(responseBody);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应快照序列化失败", ex);
        }
        repository.updateSnapshot(gate.requestId(), statusCode, json);
    }

    /**
     * 幂等闸门状态。
     */
    public record Gate(boolean enabled, String requestId, String scope, String actor,
                       String operationType, String fingerprint) {

        static Gate disabled() {
            return new Gate(false, null, null, null, null, null);
        }
    }
}
