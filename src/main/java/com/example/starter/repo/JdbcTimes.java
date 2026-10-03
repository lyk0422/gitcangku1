package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * TIMESTAMP 列与 Instant 的互转工具：列值一律按 UTC 解释，避免依赖 JVM 默认时区。
 */
final class JdbcTimes {

    private JdbcTimes() {
    }

    static LocalDateTime toColumn(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant toInstant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}