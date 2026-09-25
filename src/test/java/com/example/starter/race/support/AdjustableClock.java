package com.example.starter.race.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可调时钟：可显式推进，满足“本次交接服务端时刻必须晚于上一棒次”的时序断言。
 */
public class AdjustableClock extends Clock {

    private Instant instant;

    public AdjustableClock(Instant start) {
        this.instant = start;
    }

    /** 推进指定毫秒数。 */
    public void advanceMillis(long millis) {
        instant = instant.plusMillis(millis);
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
