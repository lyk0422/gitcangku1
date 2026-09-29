package com.example.starter.consent;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试用可控 UTC 时钟：固定或步进时间，供时间边界测试使用，不依赖真实休眠。
 */
@TestConfiguration
public class MutableClockConfig {

    /**
     * 可在测试中设置/推进的时钟。
     */
    public static class MutableClock implements DomainClock {

        private volatile Instant instant = Instant.parse("2026-09-29T00:00:00Z");

        @Override
        public Instant nowUtc() {
            return instant;
        }

        public void set(Instant instant) {
            this.instant = instant;
        }

        public void advanceMillis(long millis) {
            this.instant = instant.plusMillis(millis);
        }
    }

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }
}
