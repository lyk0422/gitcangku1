package com.example.starter.translation.domain;

import java.time.Instant;

/**
 * 冻结窗口状态判定：窗口为 UTC 左闭右开区间 [start, end)。
 */
public final class FreezeStatus {

    private FreezeStatus() {
    }

    /** 时刻落在窗口内：start &lt;= now &lt; end（左闭右开）。 */
    public static boolean isActive(Instant start, Instant end, Instant now) {
        return !now.isBefore(start) && now.isBefore(end);
    }
}
