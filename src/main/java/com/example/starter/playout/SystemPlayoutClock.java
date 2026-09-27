package com.example.starter.playout;

import org.springframework.stereotype.Component;

/**
 * 系统时钟实现：生产环境默认使用。
 */
@Component
public class SystemPlayoutClock implements PlayoutClock {

    @Override
    public long nowMs() {
        return System.currentTimeMillis();
    }
}
