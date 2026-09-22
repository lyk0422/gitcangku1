package com.example.starter.blind;

/**
 * 时钟抽象，便于测试控制时间。
 */
public interface Clock {

    /** 当前时间，Unix 毫秒，UTC。 */
    long nowMillis();
}
