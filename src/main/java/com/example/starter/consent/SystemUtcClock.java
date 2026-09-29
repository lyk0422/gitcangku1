package com.example.starter.consent;

import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 系统 UTC 时钟：生产环境使用。
 */
@Component
public class SystemUtcClock implements DomainClock {

    @Override
    public Instant nowUtc() {
        return Instant.now();
    }
}
