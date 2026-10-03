package com.example.starter;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 测试时钟配置；上下文关闭时释放H2内存库（DB_CLOSE_DELAY=-1下显式SHUTDOWN）。 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-01-01T09:00:00Z"));
    }

    @Bean
    public DisposableBean h2Shutdown(DataSource dataSource) {
        return () -> {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 上下文关闭阶段数据源可能已停止，忽略即可
            }
        };
    }
}