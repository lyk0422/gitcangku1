package com.example.starter.batch;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 可注入时钟：默认使用 UTC 系统时钟；测试可替换为固定/可控时钟以确定性验证衰减与固化时刻。
 * 所有业务时间均为 UTC instant。
 */
@Component
public class TimeSource {

    private volatile Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    /**
     * 替换底层时钟（仅供测试与可控时间场景使用）。
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * 恢复 UTC 系统时钟。
     */
    public void reset() {
        this.clock = Clock.systemUTC();
    }
}
