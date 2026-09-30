package com.example.starter.billing.support;

import com.example.starter.support.ClockService;
import java.time.Instant;

/**
 * 测试用可控时钟：随时把“当前时间”拨到指定 UTC 瞬时，不依赖真实流逝与睡眠。
 */
public class MutableClock implements ClockService {

    private volatile Instant now = Instant.parse("2026-10-01T17:30:00Z");

    @Override
    public Instant now() {
        return now;
    }

    /**
     * 拨动时钟。
     *
     * @param instant 新的当前时间（UTC）
     */
    public void setNow(Instant instant) {
        this.now = instant;
    }
}
