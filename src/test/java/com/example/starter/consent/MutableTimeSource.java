package com.example.starter.consent;

import java.time.Instant;

/**
 * 测试用可控时钟：可随时设置当前时刻，用于到期相关场景的确定性验证。
 */
public class MutableTimeSource implements TimeSource {

    private volatile Instant now;

    public MutableTimeSource(Instant initial) {
        this.now = initial;
    }

    @Override
    public Instant now() {
        return now;
    }

    public void setNow(Instant now) {
        this.now = now;
    }

    public void advanceSeconds(long seconds) {
        this.now = now.plusSeconds(seconds);
    }
}
