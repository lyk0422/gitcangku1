package com.example.starter.firmware;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 租约测试公用的可控业务时钟：固定 UTC 时区，测试显式推进时刻以验证到期边界，无需真实休眠。
 */
public final class LeaseTestClock {

    public static final Instant BASE = Instant.parse("2026-10-08T00:00:00Z");

    private LeaseTestClock() {
    }

    /** 可变时钟单例，所有测试类共享同一实例，各自在 @BeforeEach 中 reset。 */
    static final class MutableClock extends Clock {
        private volatile Instant instant = BASE;

        void reset() {
            instant = BASE;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class Config {
        static final MutableClock CLOCK = new MutableClock();

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }
}
