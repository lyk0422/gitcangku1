package com.example.starter.incident;

import java.time.Clock;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 幂等命令执行支持：commandKey 全局唯一，同键同参重放首次完整响应，同键异参 409。
 * 占位与业务写入、响应补写在同一事务内：业务失败整体回滚、不占用幂等键；
 * 并发同键由 uk_command_key 唯一约束串行化，竞争方读取已提交记录重放，
 * 不向调用方泄漏唯一键异常。
 */
@Component
public class CommandKeySupport {

    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CommandKeySupport(CommandKeyRepository commandKeys, ObjectMapper objectMapper, Clock clock) {
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 执行业务并按 commandKey 去重。调用方必须在本调用之前完成资源加锁与权限校验，
     * 使“当前权限先于重放”：无权操作人在读取任何已存响应前即被拒绝。
     */
    public <T> T run(String commandKey, String operation, String requestHash,
                     Class<T> type, Supplier<T> business) {
        var existing = commandKeys.find(commandKey);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, requestHash, type);
        }
        try {
            commandKeys.insertPlaceholder(commandKey, operation, requestHash, clock.instant());
        } catch (DuplicateKeyException e) {
            var committed = commandKeys.findForUpdate(commandKey)
                    .orElseThrow(() -> ApiException.conflict("commandKey 处理冲突: " + commandKey));
            return replay(committed, operation, requestHash, type);
        }
        T result = business.get();
        commandKeys.fillResponse(commandKey, 200, toJson(result));
        return result;
    }

    private <T> T replay(CommandKeyRecord record, String operation, String requestHash, Class<T> type) {
        if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
            throw ApiException.conflict("commandKey 已被不同参数的请求使用: " + record.commandKey());
        }
        if (record.responseBody() == null) {
            throw ApiException.conflict("commandKey 正在处理中: " + record.commandKey());
        }
        try {
            return objectMapper.readValue(record.responseBody(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
