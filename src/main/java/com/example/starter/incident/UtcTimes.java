package com.example.starter.incident;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * UTC 时间与数据库 DATETIME 列的显式互转，避免依赖会话时区。
 */
final class UtcTimes {

    private UtcTimes() {
    }

    static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
