package com.example.starter.calibration.service;

import com.example.starter.calibration.error.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * 入参解析与校验：十进制字符串（最多 6 位小数）与 ISO-8601 时间字符串。
 * 校验失败统一抛出 400。
 */
public final class InputValidation {

    /** 最多 12 位整数、6 位小数的十进制字符串，保证 a×读数+b 不超出 DECIMAL(38,12)。 */
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("^-?\\d{1,12}(\\.\\d{1,6})?$");

    private InputValidation() {
    }

    /**
     * 解析十进制字符串；格式非法时抛出 400。
     */
    public static BigDecimal parseDecimal(String field, String value) {
        if (value == null || !DECIMAL_PATTERN.matcher(value).matches()) {
            throw ApiException.badRequest(
                    "INVALID_DECIMAL",
                    field + " 必须为最多 6 位小数的十进制字符串: " + value);
        }
        return new BigDecimal(value);
    }

    /**
     * 解析 ISO-8601 时间字符串（须带偏移或 Z，如 2026-01-01T00:00:00Z）为 UTC 时刻。
     */
    public static Instant parseInstant(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_INSTANT", field + " 不能为空");
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试 Instant 格式
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest(
                    "INVALID_INSTANT",
                    field + " 必须为带时区偏移的 ISO-8601 时间: " + value);
        }
    }

    /**
     * 非空字符串校验。
     */
    public static String requireNonBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("MISSING_FIELD", field + " 不能为空");
        }
        return value;
    }
}
