package com.example.starter.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * UTC 时间转换：数据库 TIMESTAMP 列一律按 UTC 解释，与 JVM 默认时区无关。
 */
public final class UtcTimes {

    private UtcTimes() {
    }

    /**
     * Instant 转数据库值（UTC 本地日期时间）。
     */
    public static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * 数据库值（UTC 本地日期时间）转 Instant。
     */
    public static Instant fromDb(LocalDateTime dbValue) {
        return dbValue.toInstant(ZoneOffset.UTC);
    }
}