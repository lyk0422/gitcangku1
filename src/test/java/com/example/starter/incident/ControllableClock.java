package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试用可控时钟：默认指向构造时的 UTC 时刻，可通过 setInstant 推进/回退，
 * 供期限、触发与确认时刻相关测试使用。以 @Primary 覆盖生产 ClockConfig 的系统时钟。
 */
public class ControllableClock extends Clock {

    private Instant current;

    public ControllableClock(Instant initial) {
        this.current = initial;
    }

    public void setInstant(Instant instant) {
        this.current = instant;
    }

    public void advanceSeconds(long seconds) {
        this.current = current.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(current, zone);
    }

    @Override
    public Instant instant() {
        return current;
    }

    /**
     * 提供可控 Clock Bean 的测试配置。
     */
    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        public Clock controllableClock() {
            return new ControllableClock(Instant.parse("2026-09-22T00:00:00Z"));
        }
    }
}
