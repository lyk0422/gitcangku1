package com.example.starter.translation.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 业务时钟：冻结窗口按 UTC 判定。默认使用系统 UTC 时钟；测试可替换为固定/可变时钟，
 * 避免依赖真实休眠来验证窗口边界。
 */
@Component
public class BusinessClock {

    private volatile Clock clock = Clock.systemUTC();

    /** 当前时刻（UTC）。 */
    public Instant now() {
        return Instant.now(clock);
    }

    /** 替换底层时钟（主要供测试使用）。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
