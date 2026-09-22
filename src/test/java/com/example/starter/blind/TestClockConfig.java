package com.example.starter.blind;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试时钟配置：以可控时钟替换系统墙钟；
 * MutableTestClock 实现 Clock，Clock 类型注入点按 @Primary 命中该 Bean。
 * 同时注册 H2 释放 Bean，上下文结束时关闭命名内存库。
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableTestClock mutableTestClock() {
        return new MutableTestClock();
    }

    @Bean
    public H2ShutdownBean h2ShutdownBean(JdbcTemplate jdbc) {
        return new H2ShutdownBean(jdbc);
    }
}
