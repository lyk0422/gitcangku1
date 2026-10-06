package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等编排（非事务）：计算请求指纹、处理并发下 command_key 唯一约束冲突。
 * 同键同参重放返回首次结果；同键改参返回 409；并发重复插入时回滚后重放先提交事务的结果。
 */
@Component
public class IdempotencyAdvisor {

    /**
     * 唯一键冲突后的最大重试次数（含首次执行）。
     */
    static final int MAX_CONFLICT_RETRIES = 5;

    /**
     * 每次重试前的基础退避毫秒数（按尝试次数线性增长）。
     */
    static final long RETRY_BACKOFF_MILLIS = 50;

    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyAdvisor(CommandLogRepository commandLogRepository, ObjectMapper objectMapper) {
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算请求指纹：操作类型 + 操作人 + 证物键 + 请求体规范化 JSON 的 SHA-256。
     */
    public String hash(String operation, String actorId, String evidenceKey, Object request) {
        try {
            String canonical = String.join("\n",
                    operation,
                    actorId == null ? "" : actorId,
                    evidenceKey == null ? "" : evidenceKey,
                    objectMapper.writeValueAsString(request));
            return sha256(canonical);
        } catch (Exception e) {
            throw new IllegalStateException("请求序列化失败", e);
        }
    }

    /**
     * 结构化请求指纹：操作类型 + 操作人 + 资源键 + 请求体规范化 JSON 的 SHA-256。
     * 规范化规则：对象字段按名排序（顺序无关）；十进制按值比较（1.0 与 1.00 相同）；
     * 文本按原样比较（不做任何归一化）。
     */
    public String hashStructured(String operation, String actorId, String resourceKey, Object request) {
        JsonNode node = objectMapper.valueToTree(request);
        String canonical = String.join("\n",
                operation,
                actorId == null ? "" : actorId,
                resourceKey == null ? "" : resourceKey,
                canonicalize(node));
        return sha256(canonical);
    }

    private String canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            StringBuilder sb = new StringBuilder("{");
            for (String name : names) {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                sb.append('"').append(name).append("\":").append(canonicalize(node.get(name)));
            }
            return sb.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(canonicalize(node.get(i)));
            }
            return sb.append(']').toString();
        }
        if (node.isNumber()) {
            // 十进制按值比较：去除尾随零，1.0 与 1.00 归一为同一文本
            var value = node.decimalValue().stripTrailingZeros();
            return value.signum() == 0 ? "0" : value.toPlainString();
        }
        // 文本与布尔按 JSON 原样比较
        return node.toString();
    }

    private String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 在事务边界外执行命令；捕获并发导致的 command_key 或资源键唯一冲突。
     * 冲突事务可能尚未提交，做有界重试：先提交事务的日志可见后，
     * 同键同参重放首次结果，同键改参返回 409；重试耗尽仍冲突返回 409，不泄漏唯一键异常。
     */
    public StoredResponse guard(String commandKey, String requestHash,
                                Supplier<StoredResponse> transactionalCall) {
        for (int attempt = 0; attempt < MAX_CONFLICT_RETRIES; attempt++) {
            try {
                return transactionalCall.get();
            } catch (DuplicateKeyException e) {
                Optional<StoredResponse> resolved = resolveConflict(commandKey, requestHash);
                if (resolved.isPresent()) {
                    return resolved.get();
                }
                pauseBeforeRetry(attempt);
            }
        }
        Optional<StoredResponse> resolved = resolveConflict(commandKey, requestHash);
        return resolved.orElseThrow(() -> ApiException.conflict("资源键冲突，目标已存在"));
    }

    /**
     * 唯一键冲突后的判定：已存在同键日志则按指纹重放或报 409；不存在则返回空（等待重试）。
     */
    private Optional<StoredResponse> resolveConflict(String commandKey, String requestHash) {
        return commandLogRepository.findByKey(commandKey).map(log -> {
            if (log.requestHash().equals(requestHash)) {
                return new StoredResponse(log.responseStatus(), log.responseBody());
            }
            throw ApiException.conflict("幂等键已被不同参数使用: " + commandKey);
        });
    }

    private void pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS * (attempt + 1L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.conflict("资源键冲突，目标已存在");
        }
    }
}
