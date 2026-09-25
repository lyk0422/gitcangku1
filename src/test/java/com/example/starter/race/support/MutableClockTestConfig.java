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
 * 接力测试用可控时钟：起点固定，可按毫秒推进，用于验证“交接完成时刻必须晚于上一棒”。
 */
@TestConfiguration
public class MutableClockTestConfig {

    /** 测试时钟的可控实例，测试方法内可直接推进。 */
    public static final MutableClock CLOCK = new MutableClock(
            Instant.parse("2026-09-25T04:00:00Z"));

    @Bean
    @Primary
    public Clock mutableClock() {
        return CLOCK;
    }

    /** 起点固定、可人工推进的时钟。 */
    public static final class MutableClock extends Clock {

        private volatile Instant instant;

        private MutableClock(Instant start) {
            this.instant = start;
        }

        /** 将时钟向后推进指定毫秒。 */
        public void advanceMillis(long millis) {
            instant = instant.plus(Duration.ofMillis(millis));
        }

        /** 重置回固定起点（每个场景前调用）。 */
        public void reset() {
            instant = Instant.parse("2026-09-25T04:00:00Z");
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
}
