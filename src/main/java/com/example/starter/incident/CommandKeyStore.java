package com.example.starter.incident;

import java.time.Instant;
import java.util.Optional;

/**
 * 命令幂等键存储抽象：既有 {@link CommandKeyRepository} 与疏散域
 * {@link ZoneCommandKeyRepository} 物理分表但语义一致，统一由 {@link Idempotency} 驱动。
 */
public interface CommandKeyStore {

    /**
     * 按命令键普通查询。
     */
    Optional<CommandKeyRecord> find(String commandKey);

    /**
     * 按命令键锁定查询（SELECT ... FOR UPDATE）。
     */
    Optional<CommandKeyRecord> findForUpdate(String commandKey);

    /**
     * 占位插入幂等键（响应列为空）。
     */
    void insertPlaceholder(String commandKey, String operation, String requestHash, Instant now);

    /**
     * 补写首次成功响应。
     */
    void fillResponse(String commandKey, int responseStatus, String responseBody);
}
