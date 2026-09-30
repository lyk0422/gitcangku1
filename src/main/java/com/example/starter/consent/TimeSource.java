package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 统一时间来源：生产环境使用 UTC 系统时钟；测试可替换为可控时钟，用于精确验证到期边界。
 *
 * <p>承诺到期时刻以 UTC {@link Instant} 比较，到期时刻恰好到达即失效（now &gt;= expiresAt）。
 */
@Component
public class TimeSource {

    private volatile Clock clock = Clock.systemUTC();

    /**
     * 返回当前时刻（UTC）。
     */
    public Instant now() {
        return Instant.now(clock);
    }

    /**
     * 替换底层时钟，仅供测试在单线程场景下控制时间；并发测试仍以真实时钟协调真实操作。
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
