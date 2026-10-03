package com.example.starter.calibration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * JDBC 时间转换：数据库 TIMESTAMP 列一律存 UTC 墙钟时间，与 JVM 默认时区无关。
 */
final class JdbcTimes {

    private JdbcTimes() {
    }

    /** Instant 转可写入 TIMESTAMP 列的参数；null 透传。 */
    static Timestamp toParam(Instant instant) {
        return instant == null ? null : Timestamp.valueOf(toUtcLocal(instant));
    }

    /** 读取 TIMESTAMP 列为 UTC Instant；NULL 返回 null。 */
    static Instant toInstant(ResultSet rs, String column) throws SQLException {
        LocalDateTime local = rs.getObject(column, LocalDateTime.class);
        return local == null ? null : local.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime toUtcLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}