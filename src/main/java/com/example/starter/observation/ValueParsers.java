package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 新类型观测与分析共用的值解析工具：十进制读数/阈值与 UTC 整分钟时刻。
 * 所有解析失败统一转为 400 业务异常；十进制比较按数值，文本比较按原文。
 */
final class ValueParsers {

    private ValueParsers() {
    }

    /**
     * 解析十进制字符串（读数或阈值）；格式非法时抛 400。调用方已用 Bean Validation 约束格式，
     * 此处兜底并统一数值表示。
     */
    static BigDecimal parseDecimal(String value, String field) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(field + " must be a decimal string: " + value);
        }
    }

    /**
     * 十进制按值规范化（用于指纹与相等判定）：去除尾随零后的纯数字表示，"10" 与 "10.0" 等价。
     */
    static String normalizeDecimal(String value, String field) {
        return parseDecimal(value, field).stripTrailingZeros().toPlainString();
    }

    /**
     * 解析 UTC 整分钟时刻：必须是带 Z 的 ISO-8601 瞬间，且秒与更小单位为 0；否则抛 400。
     */
    static Instant parseMinuteUtc(String value) {
        Instant instant;
        try {
            instant = Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest(
                    "minuteUtc must be an ISO-8601 UTC instant (e.g. 2026-10-07T10:00:00Z): " + value);
        }
        if (instant.getEpochSecond() % 60 != 0 || instant.getNano() != 0) {
            throw ApiException.badRequest("minuteUtc must be a whole UTC minute: " + value);
        }
        return instant;
    }

    /**
     * 判断两个十进制字符串数值是否相等（任一为 null 时按原文 null 语义处理）。
     */
    static boolean decimalEquals(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
    }
}
