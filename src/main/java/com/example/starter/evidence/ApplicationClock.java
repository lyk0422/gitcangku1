package com.example.starter.evidence;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 可注入的业务时钟。生产环境使用系统默认时区（与既有时间列的 Asia/Shanghai 墙钟语义一致）；
 * 逾期判定使用 UTC 时刻。测试可替换为固定/偏移时钟，验证逾期标识而无需真实等待。
 */
@Component
public class ApplicationClock {

    private volatile Clock clock = Clock.systemDefaultZone();

    /**
     * 当前本地日期时间（落库 created_at/updated_at 等墙钟列）。
     */
    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * 当前 UTC 时刻，用于借出应还时刻与逾期判定。
     */
    public Instant instant() {
        return Instant.now(clock);
    }

    /**
     * 仅供测试替换时钟；业务代码不得调用。
     */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * 仅供测试恢复系统时钟。
     */
    void reset() {
        this.clock = Clock.systemDefaultZone();
    }
}
