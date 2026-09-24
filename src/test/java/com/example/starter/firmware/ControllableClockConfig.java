package com.example.starter.firmware;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用可控时钟：业务代码注入的 Clock 读取 HOLDER 内固定时刻，测试随时切换，不依赖真实等待。
 */
@TestConfiguration
public class ControllableClockConfig {

    public static final AtomicReference<Clock> HOLDER = new AtomicReference<>(
            Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC));

    public static void setAt(String utc) {
        HOLDER.set(Clock.fixed(Instant.parse(utc), ZoneOffset.UTC));
    }

    @Bean
    @Primary
    Clock controllableClock() {
        return new Clock() {
            @Override
            public Instant instant() {
                return HOLDER.get().instant();
            }

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }
        };
    }
}
