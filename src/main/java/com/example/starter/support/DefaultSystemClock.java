package com.example.starter.support;

import org.springframework.stereotype.Component;

/**
 * 默认系统时钟实现，直接返回 JVM 当前时间。
 */
@Component
public class DefaultSystemClock implements SystemClock {

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
