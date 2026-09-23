package com.example.starter.restitution.service;

import com.example.starter.restitution.data.RestitutionRepository;
import com.example.starter.restitution.data.RestitutionRepository.RequestRecordRow;
import com.example.starter.restitution.domain.TimeService;
import com.example.starter.restitution.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 写操作幂等执行：以全局 requestId + 操作者为键，
 * 同键同操作者同参重放首次结果，异参 409；业务失败随事务回滚且不占键。
 * 集合参数换序视为同参（按规范排序后取指纹）。
 *
 * <p>事务采用编程式控制：业务动作与幂等记录在同一事务内提交；若落幂等记录时
 * 发生唯一键冲突（同键并发），该事务整体回滚（竞争方的业务写入一并撤销），
 * 再读取首发结果重放。</p>
 */
@Component
public class IdempotentExecutor {

    private final RestitutionRepository repository;
    private final TimeService timeService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public IdempotentExecutor(RestitutionRepository repository,
                              TimeService timeService,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.timeService = timeService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 执行结果包装：携带首次/重放的 HTTP 状态码与响应体。
     */
    public record Outcome<T>(int status, T body) {
    }

    /**
     * 在同一事务内执行业务并落幂等记录。
     *
     * @param requestId     X-Request-Id
     * @param actor         X-Actor-Id
     * @param operation     操作标识（HTTP 方法+路径模板及路径变量）
     * @param requestBody   请求参数对象（参与指纹，集合按规范排序），DELETE 可为 null
     * @param responseType  首次结果/重放结果的响应类型
     * @param successStatus 首次执行成功时记录/返回的 HTTP 状态码
     * @param action        首次执行的业务动作
     */
    public <T> Outcome<T> execute(String requestId, String actor, String operation,
                                  Object requestBody, Class<T> responseType,
                                  int successStatus, Supplier<T> action) {
        if (requestId == null || requestId.isBlank()) {
            throw new ApiException(400, Map.of("error", "REQUEST_ID_REQUIRED",
                    "message", "写操作必须携带非空 X-Request-Id"));
        }
        if (actor == null || actor.isBlank()) {
            throw new ApiException(400, Map.of("error", "ACTOR_REQUIRED",
                    "message", "写操作必须携带非空 X-Actor-Id"));
        }
        String requestHash = fingerprint(operation, requestBody);

        RequestRecordRow existing = repository.findRequestRecord(requestId, actor);
        if (existing != null) {
            return replay(existing, requestHash, responseType);
        }

        @SuppressWarnings("unchecked")
        Holder<T> holder = new Holder<>();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                T result = action.get();
                holder.value = result;
                repository.saveRequestRecord(requestId, actor, requestHash,
                        successStatus, toJson(result), timeService.nowMillis());
            });
        } catch (DuplicateKeyException dup) {
            // 同键并发竞争失败：该事务已整体回滚，竞争方业务写入一并撤销；重放首发结果
            RequestRecordRow winner = repository.findRequestRecord(requestId, actor);
            if (winner == null) {
                throw new ApiException(409, Map.of(
                        "error", "IDEMPOTENCY_CONFLICT",
                        "message", "requestId 并发冲突，请重试"));
            }
            return replay(winner, requestHash, responseType);
        }
        return new Outcome<>(successStatus, holder.value);
    }

    private static final class Holder<T> {
        private T value;
    }

    private <T> Outcome<T> replay(RequestRecordRow record, String requestHash, Class<T> responseType) {
        if (!record.requestHash().equals(requestHash)) {
            throw new ApiException(409, Map.of(
                    "error", "IDEMPOTENCY_PARAM_CONFLICT",
                    "message", "相同 requestId 已用于不同参数的写操作"));
        }
        T body;
        try {
            body = objectMapper.readValue(record.responseBody(), responseType);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("重放响应反序列化失败", ex);
        }
        return new Outcome<>(record.statusCode(), body);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }

    /**
     * 计算 操作标识 + 规范参数 的 SHA-256 指纹；集合先排序，故换序同参。
     */
    String fingerprint(String operation, Object requestBody) {
        Object canonical = requestBody == null
                ? null
                : canonicalize(objectMapper.convertValue(requestBody, Object.class));
        String raw = operation + "|" + toJson(canonical);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 递归规范化：对象按键排序、集合按元素规范串排序后输出。
     */
    private Object canonicalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), canonicalize(v)));
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> parts = new ArrayList<>();
            for (Object element : iterable) {
                parts.add(toJson(canonicalize(element)));
            }
            parts.sort(String::compareTo);
            return parts;
        }
        return value;
    }
}
