package com.example.starter.translation.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 统一 UTC 时间来源。生产固定为 {@link Clock#systemUTC()}；
 * 测试可替换为固定时钟，使锚点登记、迁移、解除等时间字段可断言。
 */
@Component
public class TimeSource {

    private volatile Clock clock = Clock.systemUTC();

    /** 当前 UTC 时刻，精度截断到微秒（与 TIMESTAMP(6) 一致）。 */
    public Instant instant() {
        return Instant.now(clock);
    }

    /** 替换时钟（测试用）。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 恢复系统 UTC 时钟。 */
    public void reset() {
        this.clock = Clock.systemUTC();
    }
}
