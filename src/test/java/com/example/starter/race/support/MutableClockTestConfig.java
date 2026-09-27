package com.example.starter.race.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可变时钟：初始固定在 {@link #INITIAL_INSTANT}，测试中可显式推进，
 * 用于验证暂停区间、提交顺序与终点事件等时间相关行为。
 */
@TestConfiguration
public class MutableClockTestConfig {

    public static final Instant INITIAL_INSTANT = Instant.parse("2026-09-22T04:00:00Z");

    /** 可在测试中推进的固定时钟（始终 UTC）。 */
    public static final class MutableClock extends Clock {

        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        public void setInstant(Instant newInstant) {
            this.instant = newInstant;
        }

        public void advance(Duration duration) {
            this.instant = instant.plus(duration);
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
            return instant;
        }
    }

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(INITIAL_INSTANT);
    }
}
