package com.example.starter.translation.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 统一 UTC 时钟：业务时间全部取 UTC，区间端点按左闭右开解释。
 * 测试可替换为可控时钟；生产环境固定使用系统 UTC 时钟。
 */
@Component
public class TimeProvider {

    private Clock clock;

    public TimeProvider() {
        this(Clock.systemUTC());
    }

    public TimeProvider(Clock clock) {
        this.clock = clock;
    }

    /** 当前 UTC 时间。 */
    public Instant now() {
        return Instant.now(clock);
    }

    /** 测试支持：替换为可控时钟；生产代码不调用。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
