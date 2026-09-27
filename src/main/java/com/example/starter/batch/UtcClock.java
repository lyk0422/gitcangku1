package com.example.starter.batch;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 可注入的 UTC 时钟：默认读取系统 UTC 当前时刻；测试可固定到指定时刻以确定性验证衰减。
 *
 * <p>生产代码统一通过该组件取时，禁止直接调用 {@link Instant#now()} 做效价计算，
 * 保证放行、拆分、合批在同一命令内使用同一个计算时刻。
 */
@Component
public class UtcClock {

    private volatile Instant fixed;

    /**
     * 当前 UTC 时刻；测试固定时返回固定值，否则返回系统 UTC 时间。
     */
    public Instant instant() {
        Instant t = fixed;
        return t != null ? t : Instant.now();
    }

    /**
     * 固定时钟（仅测试使用）：固定后所有取时返回同一时刻。
     */
    public void pin(Instant instant) {
        this.fixed = instant;
    }

    /**
     * 解除固定，恢复系统时钟（仅测试使用）。
     */
    public void reset() {
        this.fixed = null;
    }
}
