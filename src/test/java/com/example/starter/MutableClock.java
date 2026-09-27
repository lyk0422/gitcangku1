package com.example.starter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：固定 UTC，可随时拨到指定 epoch 毫秒，
 * 用于确定性验证走廊“当前有效/未生效/已自然到期”语义，不依赖真实等待。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone = ZoneOffset.UTC;

    public MutableClock(long initialMillis) {
        this.instant = Instant.ofEpochMilli(initialMillis);
    }

    /** 将时钟拨到指定 epoch 毫秒（UTC）。 */
    public void setMillis(long millis) {
        this.instant = Instant.ofEpochMilli(millis);
    }

    @Override
    public ZoneId getZone() {
        return zone;
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
