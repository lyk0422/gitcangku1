package com.example.starter.consent.ledger;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

/**
 * 业务时钟：全系统一 UTC 口径，时间精度截断到微秒（与 TIMESTAMP(6) 对齐）。
 *
 * <p>生产使用系统 UTC 时钟；测试可通过 {@link #pin(Instant)} 固定、{@link #reset()} 恢复，
 * 不依赖线程休眠来判定时间相关结果。
 */
@Component
public class BusinessClock {

    private volatile Clock clock = Clock.systemUTC();

    /**
     * 当前 UTC 时间，截断到微秒，保证时间精度稳定。
     */
    public Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 固定时钟到指定时刻（测试用）。
     */
    public void pin(Instant instant) {
        this.clock = Clock.fixed(instant, java.time.ZoneOffset.UTC);
    }

    /**
     * 恢复系统 UTC 时钟（测试用）。
     */
    public void reset() {
        this.clock = Clock.systemUTC();
    }
}
