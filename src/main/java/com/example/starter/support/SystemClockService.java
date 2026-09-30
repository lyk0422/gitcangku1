package com.example.starter.support;

import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 系统墙钟实现，直接返回 JVM 当前 UTC 时间。
 */
@Component
public class SystemClockService implements ClockService {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
