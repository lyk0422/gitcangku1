package com.example.starter.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间工具：统一使用 Asia/Shanghai 时区、毫秒精度的 ISO 8601 文本表示。
 */
public final class TimeSupport {

    /** 业务时区：业务日与时间文本均按此时区解释。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private TimeSupport() {
    }

    /**
     * 解析 ISO 8601 文本（需带偏移量，毫秒精度）为 UTC 时刻；格式非法抛 400。
     */
    public static Instant parse(String text, String field) {
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("INVALID_TIME", field + " 不能为空");
        }
        try {
            return OffsetDateTime.parse(text, FORMATTER).toInstant();
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_TIME",
                    field + " 必须为毫秒精度 ISO 8601 格式，如 2026-09-21T08:00:00.000+08:00");
        }
    }

    /**
     * 将 UTC 时刻格式化为 Asia/Shanghai 时区、毫秒精度的 ISO 8601 文本。
     */
    public static String format(Instant instant) {
        return FORMATTER.format(instant.atZone(ZONE));
    }

    /**
     * 计算时刻所属业务日（Asia/Shanghai 日历日）。
     */
    public static LocalDate businessDayOf(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    /**
     * 业务日起始时刻（含）。
     */
    public static Instant dayStart(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    /**
     * 业务日结束时刻（不含）。
     */
    public static Instant dayEnd(LocalDate day) {
        return day.plusDays(1).atStartOfDay(ZONE).toInstant();
    }
}
