package com.example.starter.blind;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试数据隔离基类：每个用例前清空全部业务表，避免顺序依赖。
 */
@Import(TestClockConfig.class)
public abstract class AbstractBlindIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableTestClock clock;

    @BeforeEach
    void cleanTables() {
        // 无外键约束，顺序无依赖；全部清空保证场景独立。
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM unblind_request");
        jdbc.update("DELETE FROM allocation");
        jdbc.update("DELETE FROM seat");
        jdbc.update("DELETE FROM experiment");
        clock.setTime(1_700_000_000_000L);
    }
}
