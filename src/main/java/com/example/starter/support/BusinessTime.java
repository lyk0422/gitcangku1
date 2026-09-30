package com.example.starter.support;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 业务时间工具，默认使用 Asia/Shanghai 的系统时钟；测试可替换为可控时钟。
 */
public final class BusinessTime {

    /**
     * 业务时区，所有本地日期时间均按此时区解释。
     */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static volatile Clock clock = Clock.system(ZONE);

    private BusinessTime() {
    }

    /**
     * @return 当前业务时间
     */
    public static LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * 替换底层时钟（主要供测试使用）。
     *
     * @param newClock 新时钟
     */
    public static void setClock(Clock newClock) {
        clock = newClock;
    }

    /**
     * 恢复系统时钟。
     */
    public static void resetClock() {
        clock = Clock.system(ZONE);
    }
}
