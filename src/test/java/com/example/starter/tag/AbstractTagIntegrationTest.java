package com.example.starter.tag;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 全部 H2 集成测试的基类：共享 Spring 上下文与 H2(MODE=MySQL) 内存库，
 * 每个用例结束后清理场景业务数据，仅保留合成站点/动物基础数据。
 */
@SpringBootTest
public abstract class AbstractTagIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanScenarioData() {
        jdbcTemplate.update("DELETE FROM attachment");
        jdbcTemplate.update("DELETE FROM handoff");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("DELETE FROM tag");
    }
}
