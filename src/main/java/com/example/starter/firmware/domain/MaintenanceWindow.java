package com.example.starter.firmware.domain;

import java.time.Instant;

/**
 * 设备每日维护窗口（本地分钟，左闭右开）。
 * start == end 表示全天窗口（仅旧客户端缺省登记时出现）；start &gt; end 表示跨零点。
 *
 * @param startMinute 窗口本地开始分钟，0~1439（含）
 * @param endMinute   窗口本地结束分钟，0~1439（不含）
 */
public record MaintenanceWindow(int startMinute, int endMinute) {

    /**
     * 判断给定 UTC 时刻换算为设备本地后是否落在窗口内（左闭右开）。
     *
     * @param instant          服务器注入的 UTC 时刻
     * @param utcOffsetMinutes 设备时区偏移分钟（-720~840），本地分钟 = UTC 分钟 + 偏移
     */
    public boolean contains(Instant instant, int utcOffsetMinutes) {
        long local = Math.floorMod(instant.getEpochSecond() / 60 + utcOffsetMinutes, 1440L);
        int minute = (int) local;
        if (startMinute == endMinute) {
            return true;
        }
        if (startMinute < endMinute) {
            return minute >= startMinute && minute < endMinute;
        }
        // 跨零点：[start, 1440) ∪ [0, end)
        return minute >= startMinute || minute < endMinute;
    }

    /**
     * 计算从给定 UTC 时刻起下一次窗口开始的 UTC 时刻；恰在窗口起点（本地 start 分钟整）时返回该时刻。
     * 全天窗口恒为当前时刻。
     */
    public Instant nextStart(Instant now, int utcOffsetMinutes) {
        if (startMinute == endMinute) {
            return now;
        }
        long nowUtcMinute = now.getEpochSecond() / 60;
        long local = Math.floorMod(nowUtcMinute + utcOffsetMinutes, 1440L);
        long deltaToStart = Math.floorMod(startMinute - local, 1440L);
        long startUtcMinute = nowUtcMinute + deltaToStart;
        return Instant.ofEpochSecond(startUtcMinute * 60);
    }
}
