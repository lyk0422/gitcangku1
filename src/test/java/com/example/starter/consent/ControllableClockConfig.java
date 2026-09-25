package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试用可控时钟：以固定基准时间启动，测试中可调整到有效期之前/之内/之后，验证 UTC 左闭右开区间。
 */
@TestConfiguration
public class ControllableClockConfig {

    /** 所有委托测试共享的基准时间：2026-01-01T00:00:00Z。 */
    public static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * 可控时钟实现。
     */
    public static class ControllableClock extends Clock {

        private volatile Instant now = BASE;

        public void setInstant(Instant instant) {
            this.now = instant;
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
            return now;
        }
    }

    @Bean
    @Primary
    public Clock controllableClock() {
        return new ControllableClock();
    }
}
