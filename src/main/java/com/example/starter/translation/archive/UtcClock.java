package com.example.starter.translation.archive;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 归档领域统一 UTC 时钟：默认系统 UTC 时钟；测试可替换为可控时钟，时间测试不依赖真实睡眠。
 */
@Component
public class UtcClock {

    private volatile Clock clock = Clock.systemUTC();

    /** 当前时刻（UTC）。 */
    public Instant now() {
        return Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    /** 替换底层时钟，仅供测试装配可控时钟使用。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
