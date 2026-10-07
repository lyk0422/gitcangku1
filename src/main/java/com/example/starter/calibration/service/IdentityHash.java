package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 幂等身份哈希：结构化拼接（长度前缀防碰撞）操作者、操作、资源、全参数及期望版本后取 SHA-256。
 * 十进制参数按值归一（1.0 与 1.00 同值），文本参数原样。
 */
final class IdentityHash {

    private IdentityHash() {
    }

    /**
     * 计算请求身份哈希。各分量以 "长度:值;" 形式拼接，null 以 "-1:" 表示，避免拼接歧义。
     */
    static String of(String operation, String resource, String operator,
                     String expectedVersion, List<String> params) {
        StringBuilder sb = new StringBuilder();
        append(sb, operation);
        append(sb, resource);
        append(sb, operator);
        append(sb, expectedVersion);
        for (String param : params) {
            append(sb, param);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static void append(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("-1:");
        } else {
            sb.append(value.length()).append(':').append(value).append(';');
        }
    }

    /**
     * 十进制参数按值归一：去尾零，零统一为 "0"；非十进制或 null 原样返回（由业务校验拒绝）。
     */
    static String decimal(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.compareTo(BigDecimal.ZERO) == 0) {
                return "0";
            }
            return value.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ex) {
            return raw;
        }
    }

    /**
     * 文本参数原样。
     */
    static String text(String raw) {
        return raw;
    }
}
