package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可控时钟：固定起始时刻，可按需推进，用于确定性验证冻结到期语义。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public static MutableClock utc(Instant instant) {
        return new MutableClock(instant, ZoneId.of("UTC"));
    }

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
