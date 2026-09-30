package com.example.starter.translation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：固定在 UTC 基准时刻，可由测试显式拨动以验证冻结窗口的左闭右开边界与状态流转。
 */
public class ControlledClock extends Clock {

    /** 测试基准时刻：2026-10-01T00:00:00Z（UTC epoch 毫秒）。 */
    public static final Instant DEFAULT = Instant.parse("2026-10-01T00:00:00Z");

    private volatile Instant instant = DEFAULT;

    /** 将时钟拨到指定 UTC 时刻。 */
    public void set(Instant newInstant) {
        this.instant = newInstant;
    }

    /** 恢复到基准时刻。 */
    public void reset() {
        this.instant = DEFAULT;
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

    /** 以主时钟（{@link Primary}）替换生产的 systemUTC 时钟。 */
    @TestConfiguration
    static class Config {

        @Bean
        @Primary
        ControlledClock controlledClock() {
            return new ControlledClock();
        }
    }
}
