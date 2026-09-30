package com.example.starter.pass.clock;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 生产环境使用的系统 UTC 时钟。
 */
@Component
public class SystemUtcTimeSource implements TimeSource {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
