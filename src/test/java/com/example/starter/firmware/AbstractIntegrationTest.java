package com.example.starter.firmware;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DriverManager;

/**
 * 集成测试基类：每个用例前清空业务表，避免顺序依赖；
 * 测试类结束后对命名内存库执行 SHUTDOWN，释放延迟关闭的数据库。
 */
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_key");
    }

    /**
     * 关闭并释放指定命名内存库；由子类以各自独立库名调用。
     */
    protected static void shutdownDatabase(String jdbcUrl) throws Exception {
        try (var con = DriverManager.getConnection(jdbcUrl, "sa", "");
             var st = con.createStatement()) {
            st.execute("SHUTDOWN");
        }
    }
}
