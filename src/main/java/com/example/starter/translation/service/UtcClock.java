package com.example.starter.translation.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 业务 UTC 时钟：冲突区间命中、声明与解除时刻统一以此时钟裁决，单元/集成测试可替换为固定时钟。
 */
@Component
public class UtcClock {

    private volatile Clock clock = Clock.systemUTC();

    /** 当前 UTC 时刻。 */
    public Instant now() {
        return Instant.now(clock);
    }

    /** 当前 UTC 毫秒时间戳。 */
    public long nowMillis() {
        return now().toEpochMilli();
    }

    /** 替换底层时钟（测试使用固定时钟，避免依赖真实休眠）。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
