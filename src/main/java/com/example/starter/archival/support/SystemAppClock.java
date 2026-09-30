package com.example.starter.archival.support;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 系统墙钟实现。
 */
@Component
public class SystemAppClock implements AppClock {

    @Override
    public LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
