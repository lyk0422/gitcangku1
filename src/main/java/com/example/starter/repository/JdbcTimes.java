package com.example.starter.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * JDBC 时间转换：数据库 TIMESTAMP 列统一按 UTC 墙钟存取，与 JVM 默认时区无关。
 */
final class JdbcTimes {

    private JdbcTimes() {
    }

    /** Instant 转 SQL 时间戳：以 UTC 墙钟写入，避免受 JVM 时区影响。 */
    static Timestamp toSql(Instant instant) {
        return instant == null ? null
                : Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /** 读取 TIMESTAMP 列并按 UTC 解释；NULL 返回 null。 */
    static Instant read(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}