package com.example.starter.observation;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * UTC 时间戳 JDBC 辅助：TIMESTAMP 列统一经 JDBC 4.2 的 LocalDateTime 映射按字段原样读写，
 * 与 JVM 默认时区无关。
 *
 * <p>Instant 列存其 UTC 字段值；设备本地时刻（无区字面量）按原样字段存储，
 * 区间比较时将本地读数视为 UTC 时间线上的点（偏移量远小于区间长度的常规近似）。
 */
final class UtcJdbc {

    private UtcJdbc() {
    }

    /**
     * 将 UTC 时刻以 UTC 字段值写入 TIMESTAMP 列。
     */
    static void setInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
        ps.setObject(index, LocalDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    /**
     * 读取按 UTC 字段存储的 TIMESTAMP 列为 UTC 时刻。
     */
    static Instant getInstant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    /**
     * 将无区本地时刻按原样字段写入 TIMESTAMP 列。
     */
    static void setLocalDateTime(PreparedStatement ps, int index, LocalDateTime value) throws SQLException {
        ps.setObject(index, value);
    }

    /**
     * 读取按原样字段存储的 TIMESTAMP 列为无区本地时刻。
     */
    static LocalDateTime getLocalDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }
}
