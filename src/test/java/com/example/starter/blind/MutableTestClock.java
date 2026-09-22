package com.example.starter.blind;

/**
 * 测试用可控时钟；可手动设置当前时间，避免时间相关断言依赖真实睡眠。
 */
public class MutableTestClock implements Clock {

    private volatile long current = 1_700_000_000_000L;

    @Override
    public long nowMillis() {
        return current;
    }

    public void setTime(long millis) {
        this.current = millis;
    }

    public void advance(long millis) {
        this.current += millis;
    }
}
