package com.example.starter.consent;

import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 系统墙钟实现：返回真实 UTC 当前时刻。
 */
@Component
public class SystemTimeSource implements TimeSource {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
