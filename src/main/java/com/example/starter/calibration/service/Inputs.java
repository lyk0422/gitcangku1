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

    /** 要求非空正整数；null、小于 1 均为 400。 */
    static int requirePositive(Integer value, String field) {
        if (value == null || value < 1) {
            throw ApiException.badRequest(field + " 必须为不小于 1 的整数");
        }
        return value;
    }

    /** 修订原因：非空且长度受限，避免空原因修订。 */
    static String requireReason(String value, int maxLength, String field) {
        String reason = requireText(value, field);
        if (reason.length() > maxLength) {
            throw ApiException.badRequest(field + " 长度不能超过 " + maxLength + " 个字符");
        }
        return reason;
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
}
