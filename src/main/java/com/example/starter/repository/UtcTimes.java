package com.example.starter.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** UTC时刻与数据库DATETIME字段的互转，避免JVM默认时区影响。 */
final class UtcTimes {

    private UtcTimes() {
    }

    static Timestamp toTimestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    static Instant toInstant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
}