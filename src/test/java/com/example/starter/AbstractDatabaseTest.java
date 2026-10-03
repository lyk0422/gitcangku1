package com.example.starter;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试基类：每个用例前清理业务表，保证场景数据隔离。
 * 使用独立的 H2 命名内存库 wildlife_test（MODE=MySQL）。
 */
@SpringBootTest
public abstract class AbstractDatabaseTest {

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * 清空全部业务表（含合成演示数据），每个用例重新构造场景。
     */
    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM rejection_log");
        jdbc.update("DELETE FROM request_key");
        jdbc.update("DELETE FROM transfer");
        jdbc.update("DELETE FROM attachment");
        jdbc.update("DELETE FROM tag_ownership");
    }
}