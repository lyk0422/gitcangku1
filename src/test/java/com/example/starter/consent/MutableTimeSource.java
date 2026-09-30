package com.example.starter.consent;

import java.time.Instant;

/**
 * 测试用可控时钟：可随时设定当前 UTC 时刻，用于精确验证到期边界。
 */
public class MutableTimeSource implements TimeSource {

    private volatile Instant current;

    public MutableTimeSource() {
        this(Instant.parse("2026-09-30T10:00:00Z"));
    }

    public MutableTimeSource(Instant initial) {
        this.current = initial;
    }

    public void setTime(Instant instant) {
        this.current = instant;
    }

    @Override
    public Instant now() {
        return current;
    }
}
