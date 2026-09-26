package com.example.starter.firmware.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 业务时间工具：统一 UTC、ISO-8601、毫秒精度，保证历史记录的时间精度稳定且可字典序比较。
 */
public final class UtcTimes {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private UtcTimes() {
    }

    /**
     * 当前时刻，UTC，格式如 2026-09-26T07:00:00.000Z。
     */
    public static String now(Clock clock) {
        return FORMATTER.format(Instant.now(clock));
    }

    /**
     * 解析查询区间端点（UTC，ISO-8601），非法输入返回 null。
     */
    public static Instant parse(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
