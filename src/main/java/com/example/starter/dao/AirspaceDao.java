package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 全局空域版本单行表访问。审核与禁飞区变更均先对该行加排他锁，
 * 保证版本号与禁飞区集合来自一致状态。
 */
@Repository
public class AirspaceDao {

    private final JdbcTemplate jdbc;

    public AirspaceDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取当前全局空域版本（不加锁，仅用于只读查询）。
     */
    public long currentVersion() {
        Long version = jdbc.queryForObject("SELECT version FROM airspace_state WHERE id = 1", Long.class);
        return version == null ? 0L : version;
    }

    /**
     * 排他锁定版本行并读取版本号；须在事务内调用，用于串行化空域变更与审核。
     */
    public long lockAndGetVersion() {
        Long version = jdbc.queryForObject(
                "SELECT version FROM airspace_state WHERE id = 1 FOR UPDATE", Long.class);
        return version == null ? 0L : version;
    }

    /**
     * 全局空域版本加一；须在持有版本行排他锁的事务内调用。
     */
    public void incrementVersion() {
        jdbc.update("UPDATE airspace_state SET version = version + 1 WHERE id = 1");
    }
}
