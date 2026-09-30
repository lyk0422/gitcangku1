package com.example.starter.calibration.service;

import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 系统 UTC 时钟：生产环境使用。
 */
@Component
public class SystemBusinessClock implements BusinessClock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
