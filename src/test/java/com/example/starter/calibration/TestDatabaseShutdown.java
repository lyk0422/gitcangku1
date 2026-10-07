package com.example.starter.calibration;

import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;

/**
 * 测试数据库清理：H2 内存库配置 DB_CLOSE_DELAY=-1 时连接池关闭不会释放库，
 * 在测试上下文关闭时显式 SHUTDOWN 释放，避免跨测试类残留。
 */
@Configuration
class TestDatabaseShutdown {

    @Bean
    ApplicationListener<ContextClosedEvent> h2ShutdownOnContextClose(DataSource dataSource) {
        return event -> {
            try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
                st.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 数据库已关闭或不可用时不影响测试上下文销毁
            }
        };
    }
}
