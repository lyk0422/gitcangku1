package com.example.starter.support;

/**
 * 可替换的系统时钟，业务代码统一经此取当前 UTC 毫秒，便于时间相关测试控制。
 */
public interface SystemClock {

    /**
     * 当前时刻，UTC 毫秒。
     */
    long nowMillis();
}
