package com.example.starter.batch;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 统一 UTC 时钟：业务时间（发布、撤回、绑定、放行等）一律取此时钟，保证全部时间使用 UTC。
 *
 * <p>生产环境使用系统时钟；测试可通过 {@link #setFixed(Instant)} 固定时间或
 * {@link #advance(java.time.Duration)} 推进时间，以确定性地验证有效期左闭右开边界，
 * 避免依赖真实睡眠或墙上时钟。
 */
@Component
public class UtcClock {

    private volatile Instant fixed;

    public UtcClock() {
    }

    /**
     * 当前业务时间（UTC）。固定时钟未设置时取系统 UTC 时间。
     */
    public Instant now() {
        Instant f = fixed;
        return f != null ? f : Instant.now();
    }

    /**
     * 固定时钟到指定 UTC instant；之后 {@link #now()} 恒返回该值，直到再次设置或复位。
     */
    public void setFixed(Instant instant) {
        this.fixed = instant;
    }

    /**
     * 在当前固定时间上推进指定时长；未固定时先固定到当前系统时间再推进。
     */
    public void advance(java.time.Duration delta) {
        Instant base = fixed != null ? fixed : Instant.now();
        this.fixed = base.plus(delta);
    }

    /**
     * 恢复为系统 UTC 时钟。
     */
    public void reset() {
        this.fixed = null;
    }
}
