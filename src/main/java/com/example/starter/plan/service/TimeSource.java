package com.example.starter.plan.service;

import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 业务时钟：默认取系统 UTC 时刻；测试可通过 {@link #setFixed(Instant)} 固定时刻，
 * 用例结束须调用 {@link #reset()} 恢复系统时钟，避免用例间相互污染。
 */
@Component
public class TimeSource {

    private volatile Instant fixed;

    /**
     * 当前时刻（UTC）。固定模式下返回固定值。
     */
    public Instant now() {
        Instant snapshot = fixed;
        return snapshot != null ? snapshot : Instant.now();
    }

    /**
     * 固定当前时刻，仅测试使用。
     */
    public void setFixed(Instant instant) {
        this.fixed = instant;
    }

    /**
     * 恢复系统时钟。
     */
    public void reset() {
        this.fixed = null;
    }
}
