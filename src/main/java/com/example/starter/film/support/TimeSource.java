package com.example.starter.film.support;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 统一时钟，固定 Asia/Shanghai；测试可换固定时钟。
 */
@Component
public class TimeSource {

    /** 业务时间统一使用的时区。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private Clock clock = Clock.system(ZONE);

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * 替换时钟（仅测试使用）。
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
