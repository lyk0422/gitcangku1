package com.example.starter.water;

import com.example.starter.water.WaterRepository.CommandRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 幂等命令框架：每个写命令携带全局幂等键（commandKey/requestId），事务内先占位插入命令行
 * 再执行业务并写回响应。结构化身份 = 操作类型 + 规范化全参数串（含资源、全部参数与期望版本，
 * 十进制按同值规范化、文本原样）。同键同参重放返回首次完整响应，同键异参返回 409；
 * 业务失败事务回滚不占键；并发同键由唯一约束裁决，不向外泄漏唯一键异常。
 */
@Component
public class CommandExecutor {

    private final WaterRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public CommandExecutor(WaterRepository repository, PlatformTransactionManager transactionManager,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /** 在单事务内执行幂等命令，去重与业务同事务提交。 */
    public <T> T run(String operation, String commandKey, String params, Class<T> type,
                     Supplier<T> business) {
        try {
            return tx.execute(status -> {
                CommandRow existing = repository.findCommand(commandKey);
                if (existing != null) {
                    return replay(existing, operation, params, type);
                }
                repository.insertCommand(commandKey, operation, params, WaterService.nowNanos());
                T result = business.get();
                repository.updateCommandResponse(commandKey, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            // 并发同键：占位插入撞唯一约束，读取已提交的首次结果重放或提示重试
            CommandRow committed = repository.findCommand(commandKey);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("COMMAND_CONFLICT", "相同幂等键的命令正在处理，请重试");
            }
            return replay(committed, operation, params, type);
        }
    }

    private <T> T replay(CommandRow existing, String operation, String params, Class<T> type) {
        if (!existing.operation().equals(operation) || !existing.params().equals(params)) {
            throw ApiException.conflict("COMMAND_KEY_REUSED", "相同幂等键但参数不同，拒绝重放");
        }
        return fromJson(existing.response(), type);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }
}
