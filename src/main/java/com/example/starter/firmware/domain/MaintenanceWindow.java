package com.example.starter.firmware.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 设备本地维护窗口判定（纯函数，时钟由调用方注入）。
 * 窗口按设备本地分钟左闭右开；起始分钟大于结束分钟表示跨零点。
 */
public final class MaintenanceWindow {

    public static final int MINUTES_PER_DAY = 1440;
    public static final int MIN_OFFSET_MINUTES = -720;
    public static final int MAX_OFFSET_MINUTES = 840;

    private MaintenanceWindow() {
    }

    /**
     * 校验窗口参数：偏移-720~840，起止分钟0~1439且起止不同。
     */
    public static boolean isValid(int utcOffsetMinutes, int startMinute, int endMinute) {
        return utcOffsetMinutes >= MIN_OFFSET_MINUTES && utcOffsetMinutes <= MAX_OFFSET_MINUTES
                && startMinute >= 0 && startMinute < MINUTES_PER_DAY
                && endMinute >= 0 && endMinute < MINUTES_PER_DAY
                && startMinute != endMinute;
    }

    /**
     * 指定 UTC 时刻对应的设备本地分钟（0~1439）。
     */
    public static int localMinute(Instant utcInstant, int utcOffsetMinutes) {
        long utcMinute = Math.floorDiv(utcInstant.getEpochSecond(), 60);
        return (int) Math.floorMod(utcMinute + utcOffsetMinutes, MINUTES_PER_DAY);
    }

    /**
     * 本地分钟是否落在窗口内（左闭右开，起大于止为跨零点窗口）。
     */
    public static boolean isInWindow(int localMinute, int startMinute, int endMinute) {
        if (startMinute < endMinute) {
            return localMinute >= startMinute && localMinute < endMinute;
        }
        return localMinute >= startMinute || localMinute < endMinute;
    }

    /**
     * 指定 UTC 时刻设备是否在窗口内。
     */
    public static boolean isInWindow(Instant utcInstant, int utcOffsetMinutes, int startMinute, int endMinute) {
        return isInWindow(localMinute(utcInstant, utcOffsetMinutes), startMinute, endMinute);
    }

    /**
     * 窗口外时刻的下一次窗口开始的 UTC 时刻（分钟边界）。
     * 调用方须保证当前时刻在窗口外；若恰在窗口内则返回下一个周期的开始时刻。
     */
    public static Instant nextWindowStartUtc(Instant utcInstant, int utcOffsetMinutes, int startMinute) {
        int localMinute = localMinute(utcInstant, utcOffsetMinutes);
        int delta = (int) Math.floorMod((long) startMinute - localMinute, MINUTES_PER_DAY);
        if (delta == 0) {
            delta = MINUTES_PER_DAY;
        }
        return utcInstant.truncatedTo(ChronoUnit.MINUTES).plus(delta, ChronoUnit.MINUTES);
    }
}
