package com.example.starter.consent.migration;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 统一时钟：生产环境使用 UTC 系统时钟；测试可替换为固定时钟以验证生效窗口边界。
 */
@Component
public class TimeProvider {

    private volatile Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    /**
     * 供测试替换时钟，业务代码不要调用。
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
