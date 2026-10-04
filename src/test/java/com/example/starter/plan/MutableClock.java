package com.example.starter.plan;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可控时钟：可编程设置当前时刻，用于覆盖"计划是否已开始运行"等时间边界，
 * 不依赖真实等待或系统时间。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /**
     * 设置当前时刻。
     */
    public void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}