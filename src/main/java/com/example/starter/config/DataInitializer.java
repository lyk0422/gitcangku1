package com.example.starter.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 初始化全局空域版本单行（初始版本 0）与事务协调锁单行。
 * 建表由 schema.sql 完成；启动时先查后插，保证同一内存库内
 * 多个 Spring 上下文（如测试）重复初始化时仍然幂等。
 */
@Configuration
public class DataInitializer {

    @Bean
    @Order(0)
    ApplicationRunner initAirspaceMeta(JdbcTemplate jdbc) {
        return args -> {
            insertIfMissing(jdbc, "airspace_meta",
                    "INSERT INTO airspace_meta (id, global_version) VALUES (1, 0)");
            insertIfMissing(jdbc, "coord_lock",
                    "INSERT INTO coord_lock (id, touched) VALUES (1, 0)");
        };
    }

    private static void insertIfMissing(JdbcTemplate jdbc, String table, String insertSql) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        if (count != null && count == 0) {
            jdbc.update(insertSql);
        }
    }
}
