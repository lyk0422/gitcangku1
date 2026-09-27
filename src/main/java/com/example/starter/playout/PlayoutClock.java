package com.example.starter.playout;

import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 统一时钟：业务时间一律取 UTC 纪元毫秒。生产环境固定使用系统 UTC 时钟；
 * 测试可注入可变固定时钟，避免用休眠判断状态推进。
 */
@Component
public class PlayoutClock {

    private volatile Clock clock = Clock.systemUTC();

    /** 当前时间，UTC 纪元毫秒。 */
    public long nowMs() {
        return clock.instant().toEpochMilli();
    }

    /** 替换底层时钟，仅供测试装配使用。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
