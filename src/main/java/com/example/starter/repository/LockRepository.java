package com.example.starter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 全局事务串行锁：靠泊申请、潮高版本发布、人工复核在同一行锁上排队，
 * 保证并发时只按已提交版本完成校核，不会用旧校核结果占用新版本下不合格的泊位。
 */
@Repository
public class LockRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 模板
     */
    public LockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 锁住全局唯一锁行，事务提交或回滚后释放。
     * H2（MySQL 模式）与 MySQL 均支持 SELECT ... FOR UPDATE。
     */
    public void lockGlobal() {
        jdbcTemplate.queryForObject("SELECT id FROM app_lock WHERE id = 1 FOR UPDATE", Long.class);
    }
}
