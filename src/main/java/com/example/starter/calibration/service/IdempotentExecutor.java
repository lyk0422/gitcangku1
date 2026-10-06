package com.example.starter.calibration.service;

import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * 幂等执行入口（非事务）：校验 requestId，调用事务核心；
 * 并发同键导致唯一键冲突时不泄露异常，转为读取已提交记录重放或报 409。
 */
@Service
public class IdempotentExecutor {

    private final IdempotencyService core;

    public IdempotentExecutor(IdempotencyService core) {
        this.core = core;
    }

    /**
     * 以 requestId 为幂等键执行写操作。同键同身份重放首次完整响应，异身份 409；
     * 业务失败回滚不占键；并发竞争不泄露唯一键异常。
     */
    public <T> ResponseEntity<String> run(String requestId, String operation, String operator,
                                          String resource, Object params, HttpStatus status,
                                          Supplier<T> action) {
        String key = Inputs.requireText(requestId, "requestId", 64);
        try {
            return core.execute(key, operation, operator, resource, params, status, action);
        } catch (DuplicateKeyException ex) {
            return core.replayAfterRace(key, operation, operator, resource, params);
        }
    }
}
