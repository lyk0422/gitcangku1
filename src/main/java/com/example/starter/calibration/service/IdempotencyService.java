package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.IdempotencyRecord;
import com.example.starter.calibration.repo.IdempotencyRepository;

/**
 * 幂等去重服务：新写请求按全局 requestId 去重。
 * 结构化指纹覆盖操作、资源、全参数与期望版本；十进制按值归一（同值同参），文本原样比较。
 * 占位记录与业务写入同事务原子提交：业务失败整体回滚不占键；
 * 并发同键冲突不暴露唯一键错误，解析为重放或 409。
 */
@Service
public class IdempotencyService {

    /** 并发冲突后等待胜出事务提交可见的重试次数与间隔。 */
    private static final int RACE_RETRY_TIMES = 10;
    private static final long RACE_RETRY_MILLIS = 50;

    private final IdempotencyRepository repository;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository repository,
                              PlatformTransactionManager transactionManager,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 以幂等方式执行写操作。
     *
     * @param requestId 全局幂等请求 ID（调用方已校验非空）
     * @param operation 操作类型
     * @param actor     操作者（非空）
     * @param resource  资源标识
     * @param params    全参数（含期望版本）；String 原样比较，BigDecimal 按值归一
     * @param business  业务执行体，返回 [HTTP 状态, 响应 DTO]；抛异常则整体回滚不占键
     * @return 首次执行或重放的完整响应
     */
    public IdemOutcome execute(String requestId, String operation, String actor, String resource,
                               Map<String, Object> params, Supplier<IdemResponse> business) {
        String fingerprint = fingerprint(operation, resource, params);
        Optional<IdempotencyRecord> existing = repository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return resolve(existing.get(), fingerprint);
        }
        try {
            return transactions.execute(status -> {
                repository.insertPending(requestId, operation, actor, resource, fingerprint, Instant.now());
                IdemResponse response = business.get();
                String body = serialize(response.body());
                repository.complete(requestId, response.status(), body);
                return new IdemOutcome(response.status(), body);
            });
        } catch (DuplicateKeyException ex) {
            return resolveAfterRace(requestId, fingerprint);
        }
    }

    /**
     * 业务响应包装：HTTP 状态 + 响应 DTO。
     *
     * @param status HTTP 状态码
     * @param body   响应 DTO（序列化为 JSON 存储与返回）
     */
    public record IdemResponse(int status, Object body) {
    }

    /** 指纹命中：重放首次完整响应；指纹不同：改参 409。 */
    private IdemOutcome resolve(IdempotencyRecord record, String fingerprint) {
        if (!record.paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_PARAMS_MISMATCH",
                    "相同 requestId 的请求参数不一致: " + record.requestId());
        }
        if (record.responseStatus() == null) {
            // 理论上不会出现：记录与业务原子提交，可见记录必然已完成
            throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS",
                    "相同 requestId 的请求正在处理，请稍后重试: " + record.requestId());
        }
        return new IdemOutcome(record.responseStatus(), record.responseBody());
    }

    /** 并发同键冲突：等待胜出事务提交后解析为重放或 409，不暴露唯一键错误。 */
    private IdemOutcome resolveAfterRace(String requestId, String fingerprint) {
        for (int i = 0; i < RACE_RETRY_TIMES; i++) {
            Optional<IdempotencyRecord> record = repository.findByRequestId(requestId);
            if (record.isPresent()) {
                return resolve(record.get(), fingerprint);
            }
            try {
                Thread.sleep(RACE_RETRY_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS",
                "相同 requestId 的请求正在处理，请稍后重试: " + requestId);
    }

    /** 结构化指纹：操作、资源、全参数（含期望版本），字段无碰撞；十进制按值归一，文本原样。 */
    private String fingerprint(String operation, String resource, Map<String, Object> params) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("operation", operation);
        canonical.put("resource", resource);
        Map<String, Object> normalized = new LinkedHashMap<>();
        params.forEach((key, value) -> normalized.put(key, normalize(value)));
        canonical.put("params", normalized);
        return serialize(canonical);
    }

    /** 十进制按值归一（去尾零，零统一为 "0"）；其余类型原样。 */
    private static Object normalize(Object value) {
        if (value instanceof BigDecimal decimal) {
            BigDecimal stripped = decimal.stripTrailingZeros();
            return stripped.compareTo(BigDecimal.ZERO) == 0 ? "0" : stripped.toPlainString();
        }
        return value;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }
}
