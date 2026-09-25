package com.example.starter.race.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可变时钟：初始固定在 {@link FixedClockTestConfig#FIXED_INSTANT}，
 * 检录有效期相关用例可随时推进，无需休眠。
 */
@TestConfiguration
public class MutableClockTestConfig {

    /** 可在测试中推进的固定时钟（始终以 UTC 读数）。 */
    public static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone = ZoneOffset.UTC;

        private MutableClock(Instant initial) {
            this.instant = initial;
        }

        public void setInstant(Instant value) {
            this.instant = value;
        }

        public void advanceSeconds(long seconds) {
            this.instant = instant.plusSeconds(seconds);
        }

        public void advanceMinutes(long minutes) {
            this.instant = instant.plusSeconds(minutes * 60L);
        }

        @Override
        public ZoneId getZone() {
            return zone;
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
        return new MutableClock(FixedClockTestConfig.FIXED_INSTANT);
    }
}
