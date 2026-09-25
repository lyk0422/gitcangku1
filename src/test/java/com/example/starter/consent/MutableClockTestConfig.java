package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试用可控业务时钟：固定在某一 UTC 时刻，可手动推进，用于冻结到期判定。
 */
@TestConfiguration
public class MutableClockTestConfig {

    static final Instant INITIAL_NOW = Instant.parse("2026-09-25T00:00:00Z");

    @Bean
    @Primary
    BusinessClock businessClock() {
        return new MutableBusinessClock(Clock.fixed(INITIAL_NOW, ZoneOffset.UTC));
    }

    /** 可替换内部固定时钟的业务时钟测试替身。 */
    public static class MutableBusinessClock extends BusinessClock {

        private Clock clock;

        MutableBusinessClock(Clock clock) {
            super(clock);
            this.clock = clock;
        }

        public void setInstant(Instant instant) {
            this.clock = Clock.fixed(instant, ZoneOffset.UTC);
        }

        public void advanceSeconds(long seconds) {
            setInstant(now().plusSeconds(seconds));
        }

        @Override
        public Instant now() {
            return Instant.now(clock);
        }
    }
}
