package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import com.example.starter.calibration.api.ApiException;

/**
 * 入参校验工具：非法输入统一抛出 400。
 */
final class Inputs {

    /** 十进制字符串：可选负号，整数最多 30 位，小数最多 6 位。 */
    private static final Pattern DECIMAL = Pattern.compile("^-?\\d{1,30}(\\.\\d{1,6})?$");

    /** 非负十进制字符串：整数最多 30 位，小数最多 9 位（用于标准不确定度）。 */
    private static final Pattern NON_NEGATIVE_9 = Pattern.compile("^\\d{1,30}(\\.\\d{1,9})?$");

    /** 版本字符串：字母数字、点、下划线、连字符，1～32 位。 */
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9._-]{1,32}$");

    private Inputs() {
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.trim();
    }

    /**
     * 可选文本：空白或 null 返回 null，否则去空白。
     */
    static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static BigDecimal requireDecimal(String value, String field) {
        if (value == null || !DECIMAL.matcher(value.trim()).matches()) {
            throw ApiException.badRequest(field + " 必须为最多 6 位小数的十进制字符串");
        }
        return new BigDecimal(value.trim());
    }

    /**
     * 解析非负、最多 9 位小数的十进制；为空时返回给定默认值。
     */
    static BigDecimal optionalNonNegative(String value, String field, BigDecimal fallback) {
        String trimmed = optionalText(value);
        if (trimmed == null) {
            return fallback;
        }
        if (!NON_NEGATIVE_9.matcher(trimmed).matches()) {
            throw ApiException.badRequest(field + " 必须为最多 9 位小数的非负十进制字符串");
        }
        return new BigDecimal(trimmed);
    }

    /**
     * 解析版本字符串；为空时返回默认版本。
     */
    static String optionalVersion(String value, String fallback) {
        String trimmed = optionalText(value);
        if (trimmed == null) {
            return fallback;
        }
        if (!VERSION.matcher(trimmed).matches()) {
            throw ApiException.badRequest("版本号仅允许 1～32 位字母数字、点、下划线或连字符");
        }
        return trimmed;
    }

    static Instant requireInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        try {
            return OffsetDateTime.parse(value.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest(field + " 必须为带时区的 ISO-8601 时间");
        }
    }
}
