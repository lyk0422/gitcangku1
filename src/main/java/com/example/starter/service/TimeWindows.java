package com.example.starter.service;

import java.time.Instant;

/**
 * 左闭右开 UTC 窗口的区间规则。
 */
public final class TimeWindows {

    private TimeWindows() {
    }

    /**
     * 两个左闭右开区间是否重叠；首尾相接（aEnd == bStart）不算重叠。
     */
    public static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    /**
     * 候补窗口是否能完整落入被让出的空档：内层窗口整段包含于外层，
     * 保证锁定整段候补窗口即可且不产生双重占用。
     */
    public static boolean fitsWithin(Instant innerStart, Instant innerEnd,
                                     Instant outerStart, Instant outerEnd) {
        return !innerStart.isBefore(outerStart) && !innerEnd.isAfter(outerEnd);
    }
}
