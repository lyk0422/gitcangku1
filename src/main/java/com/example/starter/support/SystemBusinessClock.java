package com.example.starter.support;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 默认业务时钟：Asia/Shanghai 系统当前时间。
 */
@Component
public class SystemBusinessClock implements BusinessClock {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
