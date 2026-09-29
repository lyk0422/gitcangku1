package com.example.starter.calibration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试用受控 UTC 时钟：固定在某一 UTC 时刻，可显式推进；替换生产 Clock Bean，
 * 使时间相关断言确定化（不靠 sleep/当前时间猜测）。
 */
@TestConfiguration
public class ControlledClockConfig {

    /** 固定起点：UTC 2026-06-01T00:00:00（微秒精度，与 DATETIME(6) 对齐）。 */
    public static final Instant START = Instant.parse("2026-06-01T00:00:00Z");

    /**
     * 可推进的受控时钟。
     */
    public static class ControlledClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(START);
        private final ZoneId zone = ZoneOffset.UTC;

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
            return now.get();
        }

        /** 推进固定的 UTC 时长。 */
        public void advanceSeconds(long seconds) {
            now.updateAndGet(instant -> instant.plusSeconds(seconds));
        }

        /** 直接设定时刻。 */
        public void setInstant(Instant instant) {
            now.set(instant);
        }
    }

    @Bean
    @Primary
    public ControlledClock controlledClock() {
        return new ControlledClock();
    }
}
