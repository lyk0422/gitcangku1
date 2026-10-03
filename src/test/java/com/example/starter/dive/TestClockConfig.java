package com.example.starter.dive;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换业务时钟，便于断言时间字段。
 */
@TestConfiguration
public class TestClockConfig {

    /** 可推进的UTC时钟。 */
    public static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        public MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        public void set(Instant instant) {
            now.set(instant);
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
            return now.get();
        }
    }

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(Instant.parse("2026-10-04T02:00:00Z"));
    }
}