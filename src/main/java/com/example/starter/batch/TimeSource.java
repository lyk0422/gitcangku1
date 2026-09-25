package com.example.starter.batch;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 可注入时钟：业务代码统一通过本组件取当前时刻，到期判定不依赖后台任务。
 * 默认使用系统 UTC 时钟；测试可通过 {@link #set(Instant)} 固定时刻。
 */
@Component
public class TimeSource {

    private volatile Instant fixed;

    /**
     * 当前 UTC 时刻；设置了固定时刻时返回固定值，否则返回系统时钟。
     */
    public Instant now() {
        Instant value = fixed;
        return value != null ? value : Instant.now();
    }

    /**
     * 固定时钟（测试用）；传入 null 恢复系统时钟。
     */
    public void set(Instant instant) {
        this.fixed = instant;
    }

    /**
     * 恢复系统时钟。
     */
    public void reset() {
        this.fixed = null;
    }
}
