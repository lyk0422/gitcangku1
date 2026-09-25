package com.example.starter.batch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：默认固定在批次生产时间稍后，保证常规流程批次未到期；
 * 有效期相关测试可显式拨动到到期前后验证可用性判定。
 */
@TestConfiguration
public class TestClockConfig {

    /**
     * 全局基准时刻：2026-01-02T04:00:00Z，晚于测试批次生产时间 03:04:05Z。
     */
    public static final Instant BASE_INSTANT = Instant.parse("2026-01-02T04:00:00Z");

    /**
     * 可在运行中拨动当前时刻的时钟实现。
     */
    public static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        public void setInstant(Instant instant) {
            this.instant = instant;
        }

        public void advanceSeconds(long seconds) {
            this.instant = this.instant.plusSeconds(seconds);
        }

        public void advanceMinutes(long minutes) {
            this.instant = this.instant.plusSeconds(minutes * 60L);
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
        return new MutableClock(BASE_INSTANT);
    }
}
