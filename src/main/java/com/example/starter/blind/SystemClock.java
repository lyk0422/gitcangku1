package com.example.starter.blind;

import org.springframework.stereotype.Component;

/**
 * 系统墙钟实现。
 */
@Component
public class SystemClock implements Clock {

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
