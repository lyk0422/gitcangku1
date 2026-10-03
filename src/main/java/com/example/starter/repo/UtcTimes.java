package com.example.starter.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * UTC 时刻与数据库 TIMESTAMP 字段的显式转换：库中按 UTC 年月日时分秒存储，
 * 读写均不依赖 JVM 默认时区，保证日期时间统一 UTC。
 */
final class UtcTimes {

    private UtcTimes() {
    }

    /** Instant 转数据库值（UTC 墙钟时间）。 */
    static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 数据库值转 Instant（按 UTC 解释）；null 安全。 */
    static Instant fromDb(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}