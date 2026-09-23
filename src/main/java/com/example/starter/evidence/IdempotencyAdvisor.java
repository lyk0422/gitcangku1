package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
     * 计算请求指纹：操作类型 + 操作人 + 证物键 + 请求体规范化 JSON 的 SHA-256。
     */
    public String hash(String operation, String actorId, String evidenceKey, Object request) {
        try {
            String canonical = String.join("\n",
                    operation,
                    actorId == null ? "" : actorId,
                    evidenceKey == null ? "" : evidenceKey,
                    objectMapper.writeValueAsString(request));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalStateException("请求序列化失败", e);
        }
    }

    /**
     * 并发唯一冲突后等待获胜事务提交的最长时间：约 1 秒（20 次 × 50ms）。
     */
    private static final int REPLAY_WAIT_MILLIS = 50;
    private static final int REPLAY_WAIT_ATTEMPTS = 20;

    /**
     * 在事务边界外执行命令；捕获并发导致的 command_key 唯一冲突并重放/报 409。
     * 失败事务可能在获胜事务提交前一个极短窗口内被唯一约束/行锁唤醒，
     * 因此查不到首次记录时做有限次等待重试，避免把“尚未可见的重放”误判成 409。
     */
    public StoredResponse guard(String commandKey, String requestHash,
                                Supplier<StoredResponse> transactionalCall) {
        try {
            return transactionalCall.get();
        } catch (DuplicateKeyException e) {
            Optional<CommandLogRepository.CommandLog> existing = waitForCommittedLog(commandKey);
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

    /**
     * 有限次等待并发获胜事务提交后可见的命令日志；超时仍不可见返回空。
     */
    private Optional<CommandLogRepository.CommandLog> waitForCommittedLog(String commandKey) {
        Optional<CommandLogRepository.CommandLog> existing = commandLogRepository.findByKey(commandKey);
        for (int attempt = 0; existing.isEmpty() && attempt < REPLAY_WAIT_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(REPLAY_WAIT_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            existing = commandLogRepository.findByKey(commandKey);
        }
        return existing;
    }
}
