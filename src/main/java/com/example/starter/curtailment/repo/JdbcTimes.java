package com.example.starter.curtailment.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * JDBC 时间映射：TIMESTAMP 列统一按 UTC 墙钟读写，与 JVM 默认时区无关。
 */
final class JdbcTimes {

    private JdbcTimes() {
    }

    /** Instant -> 入库参数（UTC 墙钟 LocalDateTime）。 */
    static LocalDateTime toSql(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 结果集列 -> Instant（按 UTC 墙钟解释）。 */
    static Instant toInstant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
}