package com.example.starter.translation.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 发布列车时钟：默认使用系统 UTC 时钟；测试可替换为可控时钟精确驱动计划发布时刻，
 * 不靠休眠等待计划时间到达。
 */
@Component
public class TrainClock {

    private volatile Clock clock = Clock.systemUTC();

    /** 当前时刻（UTC）。 */
    public Instant now() {
        return Instant.now(clock);
    }

    /** 替换底层时钟，仅用于测试。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 恢复系统时钟。 */
    public void reset() {
        this.clock = Clock.systemUTC();
    }
}
