package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * JDBC 时间转换工具：数据库 DATETIME(6) 统一存放 UTC 墙钟时间，应用层使用 Instant。
 */
final class JdbcTimes {

    private JdbcTimes() {
    }

    static LocalDateTime toDb(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant fromDb(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
    }
}
