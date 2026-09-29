package com.example.starter.calibration.service;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.IdempotentRequest;
import com.example.starter.calibration.repo.IdempotentRequestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等执行器：同一 requestId 的请求按规范化指纹裁决——
 * 同参重放返回首次成功的完整响应快照（读取不改状态），异参返回 409，失败不占键。
 *
 * <p>事务开始即向 idempotent_request 插入 response_json 为 NULL 的占坑行：
 * <ul>
 *   <li>业务动作抛异常 → 事务回滚，占坑行随之删除，不占键；</li>
 *   <li>业务动作成功 → 回填首次完整响应 JSON 后提交；</li>
 *   <li>并发同键 → 后到者主键冲突后对该行 FOR UPDATE 等待，先到者提交后按指纹裁决，
 *       回滚则占坑行消失，后到者重试占坑，保证业务动作恰好执行一次。</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    /** 占坑冲突后重试次数上限，防御极端调度。 */
    private static final int MAX_RACE_RETRIES = 10;

    private final IdempotentRequestRepository requests;
    private final ObjectMapper objectMapper;
    private final BusinessClock clock;

    public IdempotencyService(IdempotentRequestRepository requests,
                              ObjectMapper objectMapper,
                              BusinessClock clock) {
        this.requests = requests;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 幂等执行结果。
     *
     * @param replay     是否为重放
     * @param body       首次执行时的响应对象（重放时为 null）
     * @param replayJson 重放时返回的首次原始响应 JSON（首次执行时为 null）
     */
    public record Result<T>(boolean replay, T body, String replayJson) {

        static <T> Result<T> first(T body) {
            return new Result<>(false, body, null);
        }

        static <T> Result<T> replay(String json) {
            return new Result<>(true, null, json);
        }
    }

    /**
     * 无 requestId 时直接执行业务动作，不占键不查重。
     */
    public <T> Result<T> executeWithoutIdempotency(Supplier<T> action) {
        return Result.first(action.get());
    }

    /**
     * 带 requestId 的幂等执行。
     *
     * @param requestId 客户端幂等键（已校验非空）
     * @param operation 操作类型（纳入指纹）
     * @param actor     操作者（纳入指纹）
     * @param fields    规范化参数集合及所有影响结果字段（纳入指纹）
     * @param action    业务动作（与占坑、回填同事务；抛异常则整体回滚不占键）
     */
    @Transactional
    public <T> Result<T> execute(String requestId, String operation, String actor,
                                 Map<String, ?> fields, Supplier<T> action) {
        String fingerprint = Fingerprints.of(actor, operation, fields);

        for (int attempt = 0; attempt < MAX_RACE_RETRIES; attempt++) {
            try {
                requests.insertPlaceholder(requestId, operation, fingerprint, clock.now());
            } catch (DuplicateKeyException occupied) {
                IdempotentRequest recorded = waitForWinner(requestId);
                if (recorded == null) {
                    // 先到者已回滚（失败不占键），占坑行消失，重试
                    continue;
                }
                return replay(recorded, operation, fingerprint);
            }

            // 占坑成功：执行业务动作；失败抛出由事务回滚，占坑行删除（不占键）
            T result = action.get();
            requests.complete(requestId, toJson(result));
            return Result.first(result);
        }
        throw ApiException.conflict("IDEMPOTENCY_RACE", "幂等键并发竞争未收敛: " + requestId);
    }

    /**
     * 对已存在的占坑/完成行加行锁，等待先到事务结束：
     * 提交则返回完成记录；回滚导致行消失返回 null。
     */
    private IdempotentRequest waitForWinner(String requestId) {
        var locked = requests.findByIdForUpdate(requestId);
        if (locked.isEmpty()) {
            return null;
        }
        IdempotentRequest record = locked.get();
        if (record.responseJson() == null) {
            // 理论上行锁等待结束时先到事务必已结束；防御性处理
            throw ApiException.conflict("IDEMPOTENCY_RACE", "幂等键处理中: " + requestId);
        }
        return record;
    }

    private <T> Result<T> replay(IdempotentRequest recorded, String operation, String fingerprint) {
        if (!recorded.operation().equals(operation) || !recorded.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已用于不同参数或操作的请求: " + recorded.requestId());
        }
        return Result.replay(recorded.responseJson());
    }

    private String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应快照序列化失败", ex);
        }
    }
}
