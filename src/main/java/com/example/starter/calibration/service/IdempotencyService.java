package com.example.starter.calibration.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等去重执行器。requestId 缺省时直接执行业务；携带时：
 * 同键同参重放首次完整响应，异参 409，失败不占键（业务与去重同事务原子提交）。
 * 先插入占位行再执行业务，竞争在主键上串行化，不泄露唯一键异常。
 * 指纹为操作、操作者、资源、全部参数与期望版本的结构化 JSON，避免分隔符碰撞。
 */
@Service
public class IdempotencyService {

    /** 并发竞争最大重试次数。 */
    private static final int MAX_ATTEMPTS = 3;

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 在幂等保护下执行业务。业务与去重记录在同一事务提交；业务失败整体回滚，键不被占用。
     */
    public <T> T execute(String requestId, String operation, String actor, String resource,
                         Object params, String expectedVersion, Supplier<T> business, Class<T> type) {
        if (requestId == null || requestId.isBlank()) {
            return business.get();
        }
        String id = requestId.trim();
        String fingerprint = fingerprint(operation, actor, resource, params, expectedVersion);
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.execute(status ->
                        doExecute(id, operation, actor, resource, fingerprint, business, type));
            } catch (DuplicateKeyException ex) {
                // 并发同键：占位插入冲突，本次业务随事务回滚，重试时重放已提交的首个响应
                if (attempt >= MAX_ATTEMPTS) {
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "幂等键并发竞争未决: " + id);
                }
            }
        }
    }

    private <T> T doExecute(String id, String operation, String actor, String resource,
                            String fingerprint, Supplier<T> business, Class<T> type) {
        var existing = repository.findForUpdate(id);
        if (existing.isPresent()) {
            IdempotencyRepository.Stored stored = existing.get();
            if (!stored.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同 requestId 携带不同参数");
            }
            return read(stored.responseBody(), type);
        }
        repository.insertPlaceholder(id, operation, actor, resource, fingerprint);
        T result = business.get();
        repository.storeResponse(id, write(result));
        return result;
    }

    /**
     * 结构化指纹：操作、操作者、资源、期望版本与全部参数组成有序映射后序列化为规范化 JSON。
     */
    private String fingerprint(String operation, String actor, String resource,
                               Object params, String expectedVersion) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", operation);
        identity.put("actor", actor == null ? "" : actor);
        identity.put("resource", resource == null ? "" : resource);
        identity.put("expectedVersion", expectedVersion == null ? "" : expectedVersion);
        identity.put("params", params == null ? null : objectMapper.valueToTree(params));
        try {
            return objectMapper.writeValueAsString(identity);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等指纹序列化失败", ex);
        }
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应反序列化失败", ex);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应序列化失败", ex);
        }
    }
}
