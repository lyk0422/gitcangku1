package com.example.starter.firmware.service;

import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repository.RequestLogRepository;
import com.example.starter.firmware.repository.RequestLogRepository.RequestLogRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 写操作幂等去重：同 requestId 同参数重放首次成功结果，同键异参返回 409；
 * 业务失败回滚后不占键；业务变更与去重记录在同一事务内原子提交。
 */
@Service
public class IdempotencyService {

    private final RequestLogRepository requestLogRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(RequestLogRepository requestLogRepository,
                              PlatformTransactionManager transactionManager,
                              ObjectMapper objectMapper) {
        this.requestLogRepository = requestLogRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 在单个事务内执行：查重 -> 业务 -> 登记去重记录。
     *
     * @param requestId   全局唯一请求 ID
     * @param action      业务动作标识
     * @param fingerprint 请求参数规范化指纹
     * @param business    业务逻辑，返回成功结果；抛出异常则整体回滚
     */
    public ApiResult execute(String requestId, String action, String fingerprint, Supplier<ApiResult> business) {
        try {
            return transactionTemplate.execute(status -> executeInTx(requestId, action, fingerprint, business));
        } catch (DuplicateKeyException e) {
            // 并发同键：对方事务已提交登记记录，重读并按重放/冲突处理
            return replayOrConflict(requestId, fingerprint);
        }
    }

    private ApiResult executeInTx(String requestId, String action, String fingerprint,
                                  Supplier<ApiResult> business) {
        Optional<RequestLogRow> existing = requestLogRepository.findById(requestId);
        if (existing.isPresent()) {
            return toResult(requestId, fingerprint, existing.get());
        }
        ApiResult result = business.get();
        String body = writeJson(result.body());
        requestLogRepository.insert(requestId, action, fingerprint, result.status(), body);
        return result;
    }

    private ApiResult replayOrConflict(String requestId, String fingerprint) {
        return requestLogRepository.findById(requestId)
                .map(row -> toResult(requestId, fingerprint, row))
                .orElseThrow(() -> ApiException.conflict("REQUEST_ID_CONFLICT",
                        "requestId 并发冲突，请重试: " + requestId));
    }

    private ApiResult toResult(String requestId, String fingerprint, RequestLogRow row) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 已用于不同参数的请求: " + requestId);
        }
        return new ApiResult(row.httpStatus(), readJson(row.responseBody()));
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }
}
