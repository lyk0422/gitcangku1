package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 业务时钟：所有时间按 UTC 生成；测试可替换为固定时钟以验证时间窗口边界，不依赖真实休眠。
 */
@Component
public class ServiceClock {

    private volatile Clock clock = Clock.systemUTC();

    /**
     * 当前业务时间（UTC）。
     */
    public Instant now() {
        return Instant.now(clock);
    }

    /**
     * 替换底层时钟，仅供测试注入可控时间。
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
