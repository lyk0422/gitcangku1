package com.example.starter.consent;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等语义公共组件：命中且参数一致返回原快照；参数不一致返回 409；
 * 成功结果与业务变更同事务保存，失败不占用 requestId。
 */
@Component
public class IdempotencySupport {

    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";

    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public IdempotencySupport(IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等重放检查：命中且参数一致返回原行；参数不一致返回 409；未命中返回空。
     */
    Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRow> row = idempotencyRepository.find(requestId);
        if (row.isPresent() && !row.get().paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
        }
        return row;
    }

    /**
     * 与业务变更同事务保存成功响应快照；并发同 requestId 提交时校验参数指纹一致。
     */
    void storeSuccess(String requestId, String operation, String fingerprint, Object response) {
        try {
            idempotencyRepository.insert(requestId, operation, fingerprint, writeSnapshot(response));
        } catch (DuplicateKeyException concurrent) {
            IdempotencyRow committed = idempotencyRepository.find(requestId)
                    .orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
            }
        }
    }

    String writeSnapshot(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照序列化失败", e);
        }
    }

    <T> T readSnapshot(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照反序列化失败", e);
        }
    }
}
