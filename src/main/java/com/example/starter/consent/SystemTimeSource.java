package com.example.starter.consent;

import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 系统 UTC 时钟：生产环境默认时间源。
 */
@Component
public class SystemTimeSource implements TimeSource {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
