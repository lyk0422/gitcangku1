package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyAdvisor(CommandLogRepository commandLogRepository, ObjectMapper objectMapper) {
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算请求指纹：操作类型、操作人、资源键与请求体作为结构化 JSON 数组序列化后取 SHA-256。
     * 结构化比较避免分隔符碰撞：任一字段含换行或分隔符都不会与其他组合产生相同指纹。
     */
    public String hash(String operation, String actorId, String evidenceKey, Object request) {
        try {
            String canonical = objectMapper.writeValueAsString(List.of(
                    operation,
                    actorId == null ? "" : actorId,
                    evidenceKey == null ? "" : evidenceKey,
                    objectMapper.writeValueAsString(request)));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalStateException("请求序列化失败", e);
        }
    }

    /**
     * 在事务边界外执行命令；捕获并发导致的 command_key 唯一冲突并重放/报 409，
     * 唯一键异常不向调用方泄露。
     */
    public StoredResponse guard(String commandKey, String requestHash,
                                Supplier<StoredResponse> transactionalCall) {
        try {
            return transactionalCall.get();
        } catch (DuplicateKeyException e) {
            Optional<CommandLogRepository.CommandLog> existing = commandLogRepository.findByKey(commandKey);
            if (existing.isPresent()) {
                CommandLogRepository.CommandLog log = existing.get();
                if (log.requestHash().equals(requestHash)) {
                    return new StoredResponse(log.responseStatus(), log.responseBody());
                }
                throw ApiException.conflict("幂等键已被不同参数使用: " + commandKey);
            }
            throw ApiException.conflict("资源键冲突，目标已存在");
        }
    }
}