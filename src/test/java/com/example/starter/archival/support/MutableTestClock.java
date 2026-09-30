package com.example.starter.archival.support;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 可控时钟：测试可设定/推进时间，不依赖墙钟与 sleep。
 */
public class MutableTestClock implements AppClock {

    private LocalDateTime current;

    public MutableTestClock(LocalDateTime initial) {
        this.current = initial;
    }

    @Override
    public LocalDateTime now() {
        return current;
    }

    /** 显式推进时间。 */
    public void advanceSeconds(long seconds) {
        current = current.plusSeconds(seconds);
    }

    /** 直接设定时间。 */
    public void set(LocalDateTime time) {
        this.current = time;
    }

    public static MutableTestClock startAt(int year, int month, int day, int hour, int minute) {
        return new MutableTestClock(LocalDateTime.of(year, month, day, hour, minute));
    }
}
