package com.example.starter.blind.service;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.repo.IdempotencyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 写操作幂等编排：
 * <ul>
 *   <li>权限校验由调用方在进入本服务前完成（权限先于幂等回放）；</li>
 *   <li>幂等参数包含操作者编号与角色，另加操作标识与参数指纹；</li>
 *   <li>同键同参重放原成功响应，异参返回 409；</li>
 *   <li>业务变更与幂等记录在同一事务原子提交，业务失败回滚，不占用幂等键；</li>
 *   <li>新键先插入占位行，利用主键约束串行化同键并发。</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** 同键并发占位冲突后的最大重试次数：等待胜出事务提交后回放，或其回滚后自行执行。 */
    private static final int MAX_ACQUIRE_ATTEMPTS = 10;

    /** 一次写操作的业务结果：HTTP 状态码与响应体对象。 */
    public record WriteOutcome(int status, Object body) {

        public static WriteOutcome of(int status, Object body) {
            return new WriteOutcome(status, body);
        }
    }

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    /** 指纹专用 mapper：Map 条目按键排序，保证跨进程序列化结果确定。 */
    private final ObjectMapper fingerprintMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.fingerprintMapper = objectMapper.copy()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.clock = clock;
    }

    /**
     * 计算请求参数指纹；操作标识、路径参数与请求体均参与。
     */
    public String fingerprint(String operation, Object params) {
        String json;
        try {
            json = fingerprintMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("请求参数无法序列化");
        }
        String raw = operation + "|" + json;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 在幂等保护下执行写操作；整个过程在一个事务内。
     */
    @Transactional
    public ResponseEntity<String> runWrite(String requestId, String operation, String fingerprint,
                                           Actor actor, Supplier<WriteOutcome> action) {
        for (int attempt = 0; attempt < MAX_ACQUIRE_ATTEMPTS; attempt++) {
            IdempotencyRepository.IdempotentRow existing = repository.lockByRequestId(requestId);
            if (existing != null) {
                return toReplay(existing, actor, operation, fingerprint);
            }
            boolean acquired;
            try {
                repository.insert(new IdempotencyRepository.IdempotentRow(
                        requestId, actor.actorId(), actor.role().name(), operation, fingerprint,
                        0, null, clock.nowMillis()));
                acquired = true;
            } catch (DuplicateKeyException e) {
                acquired = false;
            }
            if (acquired) {
                WriteOutcome outcome;
                try {
                    outcome = action.get();
                } catch (RuntimeException e) {
                    // 业务失败：事务回滚，占位行一并撤销，不占用幂等键。
                    throw e;
                }
                String bodyJson = toJson(outcome.body());
                repository.updateResult(requestId, outcome.status(), bodyJson);
                return jsonResponse(outcome.status(), bodyJson);
            }
            // 同键并发：等待胜出事务结束后回放；若对方回滚（行消失）则下一轮自行执行。
            IdempotencyRepository.IdempotentRow winner = repository.lockByRequestId(requestId);
            if (winner != null) {
                return toReplay(winner, actor, operation, fingerprint);
            }
        }
        throw ApiException.conflict("同 requestId 并发竞争激烈，请稍后重试");
    }

    private ResponseEntity<String> toReplay(IdempotencyRepository.IdempotentRow row, Actor actor,
                                            String operation, String fingerprint) {
        if (!row.actorId().equals(actor.actorId())
                || !row.role().equals(actor.role().name())
                || !row.operation().equals(operation)
                || !row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已用于不同参数或操作者的请求");
        }
        return jsonResponse(row.responseStatus(), row.responseBody());
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应体无法序列化", e);
        }
    }

    private ResponseEntity<String> jsonResponse(int status, String bodyJson) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(bodyJson);
    }
}
