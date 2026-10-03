package com.example.starter.bridge;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 桥梁配置仓库：读取并支持行锁（用于串行化签发决策）。
 */
@Repository
public class BridgeConfigRepository {

    private final JdbcClient jdbc;

    public BridgeConfigRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取单桥配置（id 固定为 1）。
     */
    public BridgeConfig get() {
        return jdbc.sql("SELECT max_mass_kg, window_minutes FROM bridge_config WHERE id = 1")
                .query((rs, rowNum) -> new BridgeConfig(rs.getLong(1), rs.getLong(2)))
                .single();
    }

    /**
     * 以行锁读取单桥配置，同一事务内串行化并发签发。
     */
    public BridgeConfig getForUpdate() {
        return jdbc.sql("SELECT max_mass_kg, window_minutes FROM bridge_config WHERE id = 1 FOR UPDATE")
                .query((rs, rowNum) -> new BridgeConfig(rs.getLong(1), rs.getLong(2)))
                .single();
    }
}