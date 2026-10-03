package com.example.starter.bridge;

import java.time.Instant;

/**
 * 左闭右开时间区间工具；区间终点为空表示开放区间（占用持续至今后）。
 */
public final class TimeWindows {

    private TimeWindows() {
    }

    /**
     * 判断两个左闭右开区间 [start1, end1) 与 [start2, end2) 是否相交；
     * 任一端点为 null 表示该侧无限延伸。
     */
    public static boolean overlaps(Instant start1, Instant end1, Instant start2, Instant end2) {
        boolean beforeEnd1 = end1 == null || start2.isBefore(end1);
        boolean beforeEnd2 = end2 == null || start1.isBefore(end2);
        return beforeEnd1 && beforeEnd2;
    }
}