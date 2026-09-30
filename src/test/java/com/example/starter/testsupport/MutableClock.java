package com.example.starter.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：可固定或推进，便于验证“已开始窗口不得回收”等时间边界。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private ZoneId zone = ZoneOffset.UTC;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    /** 推进固定秒数。 */
    public void advanceSeconds(long seconds) {
        this.instant = instant.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock clock = new MutableClock(instant);
        clock.zone = zone;
        return clock;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
