package com.example.starter.water;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 系统时钟：业务时间一律取 UTC 纳秒时间戳。
 * 测试可替换内部 Clock 以控制“当前时刻”（如停运是否已开始、恢复时刻校验）。
 */
@Component
public class SystemTime {

    static final long NANOS_PER_SECOND = 1_000_000_000L;

    private volatile Clock clock = Clock.systemUTC();

    /** 当前时刻，UTC 纳秒时间戳。 */
    public long nowNanos() {
        return toNanos(clock.instant());
    }

    /** 替换内部时钟（仅测试使用）。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Instant -> UTC 纳秒时间戳。 */
    public static long toNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), NANOS_PER_SECOND), instant.getNano());
    }

    /** UTC 纳秒时间戳 -> Instant。 */
    public static Instant toInstant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS_PER_SECOND),
                Math.floorMod(nanos, NANOS_PER_SECOND));
    }
}
