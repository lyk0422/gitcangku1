package com.example.starter.evidence.service;

import com.example.starter.evidence.domain.CommandLog;
import com.example.starter.evidence.domain.CommandType;
import com.example.starter.evidence.repository.CommandLogRepository;
import com.example.starter.evidence.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 幂等命令执行器：commandKey 全局唯一。同键同参重放返回首次结果；同键改参返回 409。
 * 仅成功命令落库（失败命令随事务回滚，可安全重试）；占位插入 + 唯一约束保证并发同键串行。
 */
@Service
public class CommandExecutor {

    /**
     * 参数指纹的分隔符（单元分隔符 U+001F），避免参数边界歧义。
     */
    private static final String SEPARATOR = "";

    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public CommandExecutor(CommandLogRepository commandLogRepository, ObjectMapper objectMapper) {
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 在单事务内执行命令：先登记命令占位行，再执行业务，成功后回填首次结果。
     *
     * @param commandKey  幂等键
     * @param type        命令类型
     * @param actorId     操作人（X-Actor-Id）
     * @param fingerprint 规范化参数指纹（{@link #fingerprintOf}）
     * @param action      业务动作，抛 {@link ApiException} 时整体回滚
     */
    @Transactional
    public CommandResult execute(String commandKey, CommandType type, String actorId,
                                 String fingerprint, Supplier<CommandResult> action) {
        try {
            commandLogRepository.insertPending(commandKey, type, actorId, fingerprint, LocalDateTime.now());
        } catch (DuplicateKeyException duplicate) {
            return replay(commandKey, type, fingerprint);
        }
        CommandResult result = action.get();
        commandLogRepository.complete(commandKey, result.httpStatus(), toJson(result.body()));
        return result;
    }

    private CommandResult replay(String commandKey, CommandType type, String fingerprint) {
        CommandLog log = commandLogRepository.findByKey(commandKey)
                .orElseThrow(() -> ApiException.conflict("commandKey 冲突，请重试"));
        if (log.commandType() != type || !log.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("commandKey 已被不同参数的命令使用");
        }
        if (log.httpStatus() == null) {
            throw ApiException.conflict("commandKey 对应命令正在处理中，请稍后重试");
        }
        return new CommandResult(log.httpStatus(), fromJson(log.responseBody()));
    }

    /**
     * 计算规范化参数指纹：命令类型 + 操作人 + 各参数按序拼接后的 SHA-256 十六进制。
     */
    public static String fingerprintOf(String... parts) {
        String canonical = String.join(SEPARATOR, parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private Object fromJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }
}
