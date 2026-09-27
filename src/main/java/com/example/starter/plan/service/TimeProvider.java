package com.example.starter.plan.service;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 业务时钟：判定"计划是否已开始"等时间相关规则的统一时间源。
 * 生产默认系统 UTC 时钟；测试可替换为固定时钟，保证时间相关用例可控。
 */
@Component
public class TimeProvider {

    private volatile Clock clock = Clock.systemUTC();

    /**
     * 当前时刻（UTC）。
     */
    public Instant now() {
        return clock.instant();
    }

    /**
     * 当前时刻（UTC 毫秒）。
     */
    public long nowMillis() {
        return clock.millis();
    }

    /**
     * 替换时钟（测试用）。
     */
    public void setClock(Clock newClock) {
        this.clock = newClock;
    }

    /**
     * 恢复系统 UTC 时钟。
     */
    public void reset() {
        this.clock = Clock.systemUTC();
    }
}
