package com.example.starter.calibration.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

/**
 * 领域时钟：默认使用 UTC 系统时钟；测试可替换为固定时刻，
 * 以确定性地验证系数版本生效区间左闭右开等时间边界。
 */
@Component
public class DomainClock extends Clock {

    private volatile Clock delegate = Clock.systemUTC();

    /** 固定到指定 UTC 时刻。 */
    public void setInstant(Instant instant) {
        this.delegate = Clock.fixed(instant, ZoneOffset.UTC);
    }

    /** 恢复为 UTC 系统时钟。 */
    public void reset() {
        this.delegate = Clock.systemUTC();
    }

    @Override
    public ZoneId getZone() {
        return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return delegate.withZone(zone);
    }

    @Override
    public Instant instant() {
        return delegate.instant();
    }
}
