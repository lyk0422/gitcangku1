package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * UTC 时刻与数据库 TIMESTAMP 的双向转换：列类型为不带时区的 TIMESTAMP，
 * 统一按 UTC 解释，避免依赖 JVM 默认时区。
 */
final class Times {

    private Times() {
    }

    /**
     * Instant（UTC）转为数据库 TIMESTAMP。
     */
    static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /**
     * 数据库 TIMESTAMP 转为 Instant（按 UTC 解释）。
     */
    static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}