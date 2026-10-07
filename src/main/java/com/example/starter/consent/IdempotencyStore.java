package com.example.starter.consent;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等存储支撑：供各域服务共用的重放检查、成功快照保存与序列化。
 *
 * <p>规则：成功结果与业务变更同事务保存；同一 requestId 相同参数重试返回原快照，
 * 参数变更返回 409；失败请求不占用 requestId；并发同 requestId 不泄漏唯一键异常。
 */
@Component
public class IdempotencyStore {

    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyStore(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Optional<IdempotencyRow> find(String requestId) {
        return repository.find(requestId);
    }

    /**
     * 已存行的参数指纹与本次不一致时抛出 409。
     */
    public void requireSameParams(IdempotencyRow row, String fingerprint) {
        if (!row.paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
        }
    }

    /**
     * 保存成功结果快照；并发同 requestId 时校验已提交快照参数一致，否则视为冲突。
     */
    public void storeSuccess(String requestId, String operation, String fingerprint, Object response,
                             String subjectKey, Purpose purpose, Integer epoch) {
        try {
            repository.insert(requestId, operation, fingerprint, writeSnapshot(response),
                    subjectKey, purpose, epoch);
        } catch (DuplicateKeyException concurrent) {
            IdempotencyRow committed = repository.find(requestId)
                    .orElseThrow(() -> concurrent);
            requireSameParams(committed, fingerprint);
        }
    }

    public String writeSnapshot(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照序列化失败", e);
        }
    }

    public <T> T readSnapshot(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照反序列化失败", e);
        }
    }
}
