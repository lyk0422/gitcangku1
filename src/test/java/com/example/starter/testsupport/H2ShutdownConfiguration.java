package com.example.starter.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/** 测试上下文结束后释放 H2 命名内存库，避免在 JVM 内残留。 */
@TestConfiguration
public class H2ShutdownConfiguration {

    /** 上下文关闭时执行 H2 SHUTDOWN，释放测试内存库。 */
    @Bean
    ShutdownListener h2ShutdownListener(JdbcTemplate jdbcTemplate) {
        return new ShutdownListener(jdbcTemplate);
    }

    static class ShutdownListener {
        private final JdbcTemplate jdbcTemplate;

        ShutdownListener(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @EventListener(ContextClosedEvent.class)
        public void onClosed() {
            try {
                jdbcTemplate.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 上下文关闭阶段库可能已释放，忽略即可
            }
        }
    }
}
