package com.example.starter;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试基础设施：可控时钟替代系统时钟；上下文关闭时释放 H2 内存库。
 */
@TestConfiguration
public class TestInfraConfig {

    /** 可控时钟，初始固定在 2026-10-04T00:00:00Z。 */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
    }

    /** 测试上下文关闭时执行 SHUTDOWN，释放 DB_CLOSE_DELAY=-1 持有的内存库。 */
    @Bean
    public H2Shutdown h2Shutdown(DataSource dataSource) {
        return new H2Shutdown(dataSource);
    }

    /** 在 Spring 上下文销毁阶段关闭 H2 内存库。 */
    public static final class H2Shutdown implements org.springframework.beans.factory.DisposableBean {

        private final DataSource dataSource;

        H2Shutdown(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public void destroy() {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 数据库可能已随 JVM 关闭钩子释放，忽略重复关闭。
            }
        }
    }
}