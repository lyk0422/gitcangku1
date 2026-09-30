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

    private Inputs() {
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.trim();
    }

    /**
     * 解析最多 6 位小数的十进制字符串。
     */
    static BigDecimal requireDecimal(String value, String field) {
        return requireDecimal(value, field, 6);
    }

    /**
     * 解析最多 maxScale 位小数的十进制字符串：可选负号，整数最多 30 位。
     */
    static BigDecimal requireDecimal(String value, String field, int maxScale) {
        if (value == null) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        Pattern exact = Pattern.compile("^-?\\d{1,30}(\\.\\d{1," + maxScale + "})?$");
        if (!exact.matcher(value.trim()).matches()) {
            throw ApiException.badRequest(field + " 必须为最多 " + maxScale + " 位小数的十进制字符串");
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
}
