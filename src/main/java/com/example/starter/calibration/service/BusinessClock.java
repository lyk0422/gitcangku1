package com.example.starter.calibration.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 业务时钟：默认使用 UTC 系统时钟；测试可替换为固定/可控时钟，所有时间均以 UTC 取值。
 */
@Component
public class BusinessClock {

    private volatile Clock clock = Clock.systemUTC();

    /**
     * 读取当前时刻（UTC）。
     */
    public Instant now() {
        return Instant.now(clock);
    }

    /**
     * 替换底层时钟（仅供测试装配）。
     */
    public void setClock(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }
}
