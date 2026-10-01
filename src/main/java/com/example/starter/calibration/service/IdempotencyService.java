package com.example.starter.calibration.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * 幂等服务：requestId 同参重放首次成功结果，异参返回 409；仅成功请求占键。
 * 键的插入与业务写入同事务，失败回滚后不占键。
 */
@Service
public class IdempotencyService {

    /**
     * 查找可重放的首次结果。requestId 不存在返回 empty；存在但参数指纹或接口不一致抛 409。
     */
    public <T> Optional<T> findReplay(String requestId, String endpoint, String fingerprint, Class<T> type) {
        throw new UnsupportedOperationException();
    }

    /**
     * 存储首次成功结果（须在业务事务内调用）。
     */
    public <T> void store(String requestId, String endpoint, String fingerprint, T response) {
        throw new UnsupportedOperationException();
    }
}