package com.example.starter.batch;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 可注入的服务端时钟：默认返回系统 UTC 当前时刻；到期判定、有效期顺延与时间戳均以此为准。
 *
 * <p>测试可通过 {@link #setFixed(Instant)} 固定时刻、{@link #advanceSeconds(long)} 推进，
 * 或 {@link #reset()} 恢复系统时钟；生产代码不应调用这些修改方法。
 */
@Component
public class TimeProvider {

    private volatile Instant fixed;

    /**
     * 当前时刻：固定时钟优先，否则取系统 UTC 时间。
     */
    public Instant now() {
        Instant value = fixed;
        return value != null ? value : Instant.now();
    }

    public void setFixed(Instant instant) {
        this.fixed = instant;
    }

    public void advanceSeconds(long seconds) {
        Instant value = fixed;
        this.fixed = (value != null ? value : Instant.now()).plusSeconds(seconds);
    }

    public void reset() {
        this.fixed = null;
    }
}
