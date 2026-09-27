package com.example.starter.water;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 幂等命令执行器：单事务内“占位命令行 → 执行业务 → 写回首次成功响应快照”。
 *
 * <p>同 commandKey 同参（指纹）重放：返回首次成功响应 JSON 的反序列化快照，不执行业务、不改状态；
 * 同键异参返回 409；业务失败随事务回滚，命令行一并回滚（失败不占键）。</p>
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
        // 指纹载体均为 record（组件声明顺序确定）；开启 Map 键排序保证任意 Map 载荷指纹确定
        this.objectMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * 执行幂等命令。
     *
     * @param operation   操作类型，纳入指纹
     * @param commandKey  幂等键
     * @param fingerprint 规范化参数结构（覆盖操作者、操作类型、规范化集合与全部结果相关字段；
     *                    集合以 JSON 数组转义序列化，不能直接以未转义分隔符拼接）
     * @param type        首次响应快照的反序列化类型
     * @param business    事务内业务逻辑，返回结果将作为首次响应快照保存
     */
    public <T> T run(String operation, String commandKey, Object fingerprint, Class<T> type,
                     Supplier<T> business) {
        String params = toFingerprint(operation, fingerprint);
        try {
            return tx.execute(status -> {
                WaterRepository.CommandRow existing = repository.findCommand(commandKey);
                if (existing != null) {
                    return replay(existing, operation, params, type);
                }
                repository.insertCommand(commandKey, operation, params, WaterService.nowNanos());
                T result = business.get();
                repository.updateCommandResponse(commandKey, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            WaterRepository.CommandRow committed = repository.findCommand(commandKey);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("COMMAND_CONFLICT", "相同 commandKey 的命令正在处理，请重试");
            }
            return replay(committed, operation, params, type);
        }
    }

    private <T> T replay(WaterRepository.CommandRow existing, String operation, String params, Class<T> type) {
        if (!existing.operation().equals(operation) || !existing.params().equals(params)) {
            throw ApiException.conflict("COMMAND_KEY_REUSED", "相同 commandKey 但参数不同，拒绝重放");
        }
        return fromJson(existing.response(), type);
    }

    private String toFingerprint(String operation, Object payload) {
        try {
            // 包装为单键对象：操作类型显式纳入指纹；payload 自身可携带 actor 与规范化集合。
            // objectMapper 副本已开启 SORT_PROPERTIES_ALPHABETICALLY，嵌套 Map 键序确定。
            return objectMapper.writeValueAsString(new FingerprintEnvelope(operation, payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("命令指纹序列化失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
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

    /** 指纹信封：固定字段名，操作类型置于指纹最外层。 */
    private record FingerprintEnvelope(String operation, Object parameters) {
    }
}
