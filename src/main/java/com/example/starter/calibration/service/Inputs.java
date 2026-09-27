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

    private Inputs() {
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.trim();
    }

    static BigDecimal requireDecimal(String value, String field) {
        if (value == null || !DECIMAL.matcher(value.trim()).matches()) {
            throw ApiException.badRequest(field + " 必须为最多 6 位小数的十进制字符串");
        }
        return new BigDecimal(value.trim());
    }

    /**
     * 非负十进制（最多 6 位小数）。格式无法解析抛 400；可解析但为负抛 422（数值非法）。
     */
    static BigDecimal requireNonNegativeDecimal(String value, String field) {
        if (value == null || !DECIMAL.matcher(value.trim()).matches()) {
            throw ApiException.badRequest(field + " 必须为最多 6 位小数的十进制字符串");
        }
        BigDecimal parsed = new BigDecimal(value.trim());
        if (parsed.signum() < 0) {
            throw ApiException.unprocessable("INVALID_NONNEGATIVE_VALUE", field + " 不能为负");
        }
        return parsed;
    }

    /**
     * 可空的 ISO-8601 时间：null/空白返回 null（用于开放区间终点）；有值则解析。
     */
    static Instant requireOptionalInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireInstant(value, field);
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
