package com.example.starter.curtailment.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * UTC 时间换算：数据库 DATETIME 列一律按 UTC 解释，应用层对外使用 Instant。
 */
public final class UtcTimes {

    private UtcTimes() {
    }

    public static LocalDateTime toUtcDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant toInstant(LocalDateTime utcDateTime) {
        return utcDateTime == null ? null : utcDateTime.toInstant(ZoneOffset.UTC);
    }
}
