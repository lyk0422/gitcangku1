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

    /**
     * 要求时刻为毫秒精度（UTC 毫秒时刻）；亚毫秒精度视为超精度输入，返回 400。
     */
    static void requireMillisPrecision(Instant instant, String field) {
        if (instant.getNano() % 1_000_000 != 0) {
            throw ApiException.badRequest(field + " 必须为毫秒精度（不允许亚毫秒）");
        }
    }
}
