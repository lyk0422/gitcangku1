package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 写操作幂等执行骨架：与 ObservationService 内私有实现同一语义，供围栏、提交、裁决等
 * 新写操作复用。调用方须在事务内使用：
 * <ol>
 *   <li>{@link #findExistingOrThrow}：同键同参返回已存成功记录，同键异参抛 409，无记录返回 null；</li>
 *   <li>{@link #insertPlaceholderOrNull}：占位插入去重记录；并发同键时读取已提交结果返回，
 *       正常占位返回 null；业务失败时占位随事务回滚，不占键；</li>
 *   <li>{@link #complete}：业务成功后回填响应快照，与业务变更同事务提交。</li>
 * </ol>
 */
@Component
public class IdempotentRequests {

    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    public IdempotentRequests(RequestLogRepository requestLogRepository, ObjectMapper objectMapper) {
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等检查：同键同参返回已存成功记录；同键异参抛 409；无记录返回 null 继续执行。
     */
    public RequestLogEntry findExistingOrThrow(String requestId, String fingerprint) {
        return requestLogRepository.find(requestId)
                .map(entry -> {
                    if (!entry.fingerprint().equals(fingerprint)) {
                        throw ApiException.conflict(
                                "requestId reused with different parameters: " + requestId, null);
                    }
                    return entry;
                })
                .orElse(null);
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回该记录，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    public RequestLogEntry insertPlaceholderOrNull(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict(
                        "requestId reused with different parameters: " + requestId, null);
            }
            return entry;
        }
    }

    /**
     * 业务成功后回填去重记录的响应快照（JSON 原文），与业务变更在同一事务内提交。
     */
    public void complete(String requestId, int responseStatus, Object responseBody) {
        try {
            requestLogRepository.complete(requestId, responseStatus,
                    objectMapper.writeValueAsString(responseBody));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    /**
     * 读取已存成功记录的响应快照（重放路径）；读取不改变任何状态。
     */
    public <T> T readStoredBody(RequestLogEntry entry, Class<T> bodyType) {
        try {
            return objectMapper.readValue(entry.responseBody(), bodyType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }
}
