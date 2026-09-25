package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 业务时钟：冻结到期判定统一基于此时钟，测试可替换为固定或可调时钟（UTC）。
 */
@Component
public class BusinessClock {

    private final Clock clock;

    public BusinessClock() {
        this(Clock.systemUTC());
    }

    public BusinessClock(Clock clock) {
        this.clock = clock;
    }

    /** 当前 UTC 时刻。 */
    public Instant now() {
        return Instant.now(clock);
    }
}
