package com.example.starter.curtailment.common;

import com.example.starter.curtailment.error.ApiException;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 入参校验工具：所有失败均抛出 400 语义的 ApiException。
 */
public final class Check {

    /** 功率小数位上限。 */
    public static final int POWER_SCALE = 3;

    private Check() {
    }

    public static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.trim();
    }

    public static Instant requireTime(Instant value, String field) {
        if (value == null) {
            throw ApiException.badRequest(field + " 不能为空，须为 UTC 时间");
        }
        return value;
    }

    public static void requireInterval(Instant from, Instant to, String fromField, String toField) {
        requireTime(from, fromField);
        requireTime(to, toField);
        if (!from.isBefore(to)) {
            throw ApiException.badRequest(fromField + " 必须早于 " + toField);
        }
    }

    /**
     * 解析十进制功率字符串：最多 3 位小数；positive 为真时必须大于零。
     */
    public static BigDecimal parsePower(String raw, String field, boolean positive) {
        String text = requireText(raw, field);
        BigDecimal value;
        try {
            value = new BigDecimal(text);
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest(field + " 必须是十进制数字");
        }
        if (value.scale() > POWER_SCALE) {
            throw ApiException.badRequest(field + " 最多 " + POWER_SCALE + " 位小数");
        }
        if (positive && value.signum() <= 0) {
            throw ApiException.badRequest(field + " 必须大于零");
        }
        return value;
    }

    /** 输出功率字符串：去除多余尾零，保持十进制可读形式。 */
    public static String formatPower(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
