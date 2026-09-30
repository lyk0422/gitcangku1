package com.example.starter.calibration.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 统一时间口径：全部业务时间戳（UTC，微秒精度）经本时钟产生。
 * 默认使用系统 UTC 时钟；测试可替换为固定/可控时钟，不依赖 Thread.sleep 判定时序。
 */
@Component
public class CalibrationClock {

    private volatile Clock clock = Clock.systemUTC();

    /** 当前时刻（UTC）。 */
    public Instant now() {
        return Instant.now(clock);
    }

    /** 替换时钟（测试用），如 Clock.fixed 或可控的 MutableClock。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 恢复系统 UTC 时钟（测试清理用）。 */
    public void reset() {
        this.clock = Clock.systemUTC();
    }
}
