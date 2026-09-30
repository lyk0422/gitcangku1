package com.example.starter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * 每个测试方法前清空业务表（内存库随 JVM 退出释放，这里仅隔离场景数据）。
 */
public class ResetDatabaseTestExecutionListener extends AbstractTestExecutionListener {

    @Override
    public void beforeTestMethod(TestContext testContext) {
        JdbcTemplate jdbcTemplate = testContext.getApplicationContext().getBean(JdbcTemplate.class);
        jdbcTemplate.update("DELETE FROM instrument_slot");
        jdbcTemplate.update("DELETE FROM observation_request");
        jdbcTemplate.update("DELETE FROM idempotent_call");
        jdbcTemplate.update("DELETE FROM instrument");
    }
}
