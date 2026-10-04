package com.example.starter.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    /**
     * 初始化合成演示数据（本地入口默认开启；测试通过 app.demo-data.enabled=false 关闭）。
     * 仅在对应表为空时插入，同一内存库重复初始化保持幂等。
     */
    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "app.demo-data", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    ApplicationRunner initDemoData(JdbcTemplate jdbc) {
        return args -> {
            Integer zones = jdbc.queryForObject("SELECT COUNT(*) FROM no_fly_zone", Integer.class);
            if (zones != null && zones == 0) {
                jdbc.update("INSERT INTO no_fly_zone "
                        + "(zone_id, x_min, y_min, x_max, y_max, status, created_version, revoked_version) "
                        + "VALUES ('demo-zone', 400, 400, 600, 600, 'ACTIVE', 1, NULL)");
                jdbc.update("UPDATE airspace_meta SET global_version = 1 WHERE id = 1");
            }
            Integer routes = jdbc.queryForObject("SELECT COUNT(*) FROM route", Integer.class);
            if (routes != null && routes == 0) {
                jdbc.update("INSERT INTO route (route_id, version, touch) VALUES ('demo-route', 1, 0)");
                jdbc.update("INSERT INTO route_point (route_id, seq, x, y) VALUES ('demo-route', 0, 0, 0)");
                jdbc.update("INSERT INTO route_point (route_id, seq, x, y) VALUES ('demo-route', 1, 1000, 0)");
            }
        };
    }

    private static void insertIfMissing(JdbcTemplate jdbc, String table, String insertSql) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        if (count != null && count == 0) {
            jdbc.update(insertSql);
        }
    }
}
