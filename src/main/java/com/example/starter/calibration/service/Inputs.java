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

    /** 温度/湿度字符串：可选负号（温度可为负），整数最多 10 位，小数最多 4 位。 */
    private static final Pattern ENV_DECIMAL = Pattern.compile("^-?\\d{1,10}(\\.\\d{1,4})?$");

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

    /** 可选十进制（最多 6 位小数）；空白视为未提供（null）。 */
    static BigDecimal optionalDecimal(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireDecimal(value, field);
    }

    /** 环境量（温度/湿度，最多 4 位小数）；必填语义由调用方保证。 */
    static BigDecimal requireEnvDecimal(String value, String field) {
        if (value == null || !ENV_DECIMAL.matcher(value.trim()).matches()) {
            throw ApiException.badRequest(field + " 必须为最多 4 位小数的十进制字符串");
        }
        return new BigDecimal(value.trim());
    }

    /** 要求非负。 */
    static BigDecimal requireNonNegative(BigDecimal value, String field) {
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest(field + " 不能为负数");
        }
        return value;
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
