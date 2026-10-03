package com.example.starter.kiln;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试配置：可控时钟替代系统时钟；上下文关闭时释放 H2 内存库（配合 DB_CLOSE_DELAY=-1）。
 */
@TestConfiguration
public class TestKilnConfig {

    /** 可推进的时钟，避免测试依赖真实时间。 */
    public static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        public MutableClock(Instant initial) {
            this.instant = new AtomicReference<>(initial);
        }

        public void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-02-01T00:00:00Z"));
    }

    /** 测试上下文关闭时执行 SHUTDOWN，释放 DB_CLOSE_DELAY=-1 持有的内存库。 */
    @Bean
    public H2Releaser h2Releaser(JdbcTemplate jdbc) {
        return new H2Releaser(jdbc);
    }

    public static final class H2Releaser {
        private final JdbcTemplate jdbc;

        H2Releaser(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @PreDestroy
        void release() {
            jdbc.execute("SHUTDOWN");
        }
    }
}