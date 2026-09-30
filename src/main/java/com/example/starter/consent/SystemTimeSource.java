package com.example.starter.consent;

import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 系统 UTC 时钟时间源。
 */
@Component
public class SystemTimeSource implements TimeSource {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
