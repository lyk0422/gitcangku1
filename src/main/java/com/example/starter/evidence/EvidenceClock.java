package com.example.starter.evidence;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 可注入时钟：借出/归还与逾期判定统一从该时钟取当前 UTC 时刻。
 * 默认 systemUTC()；测试可替换为固定或偏移时钟以验证逾期边界，无需真实等待。
 */
@Component
public class EvidenceClock {

    private Clock clock = Clock.systemUTC();

    /**
     * 当前使用的时钟。
     */
    public Clock clock() {
        return clock;
    }

    /**
     * 替换时钟（测试使用）。
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * 当前 UTC 时刻（无时区偏移量的 LocalDateTime，按 UTC 解释）。
     */
    public LocalDateTime nowUtc() {
        return LocalDateTime.now(clock);
    }
}
