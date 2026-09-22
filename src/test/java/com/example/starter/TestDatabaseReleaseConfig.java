package com.example.starter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试配置：测试上下文关闭时显式释放 H2 内存库（测试库使用 DB_CLOSE_DELAY=-1）。
 */
@Configuration
public class TestDatabaseReleaseConfig {

    /**
     * 上下文销毁时关闭 H2 内存库，避免跨测试上下文残留。
     */
    @Bean
    public H2DatabaseReleaser h2DatabaseReleaser(JdbcTemplate jdbcTemplate) {
        return new H2DatabaseReleaser(jdbcTemplate);
    }

    /**
     * 在 Spring 容器关闭阶段执行 H2 SHUTDOWN。
     */
    public static final class H2DatabaseReleaser implements AutoCloseable {

        private final JdbcTemplate jdbcTemplate;

        H2DatabaseReleaser(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public void close() {
            jdbcTemplate.execute("SHUTDOWN");
        }
    }
}
