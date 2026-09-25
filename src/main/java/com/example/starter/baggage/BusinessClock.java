package com.example.starter.baggage;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

/**
 * 业务时钟：统一以 Asia/Shanghai 会话时区生成复重、提醒清除等业务时刻。
 * 默认走系统时钟；测试可通过 {@link #useFixed} 注入可控时钟。
 */
@Component
public class BusinessClock {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private volatile Clock clock = Clock.system(ZONE);

    /** 当前业务时刻。 */
    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 业务时区。 */
    public ZoneId zone() {
        return ZONE;
    }

    /** 测试专用：固定到指定时刻。 */
    void useFixed(LocalDateTime time) {
        this.clock = Clock.fixed(time.atZone(ZONE).toInstant(), ZONE);
    }

    /** 测试专用：恢复系统时钟。 */
    void useSystem() {
        this.clock = Clock.system(ZONE);
    }
}
