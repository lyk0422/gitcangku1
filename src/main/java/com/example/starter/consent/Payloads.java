package com.example.starter.consent;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 载荷规范化工具：十进制按同值规范化（1.0 与 1.00 视为同参），文本保持原样；
 * 指纹只保存 SHA-256 哈希，不残留载荷原文。
 */
final class Payloads {

    private Payloads() {
    }

    /**
     * 规范化载荷：可解析为十进制时按数值同值规范化（去尾零、零值统一为 "0"），否则文本原样返回。
     */
    static String canonical(String payload) {
        try {
            BigDecimal decimal = new BigDecimal(payload);
            if (decimal.signum() == 0) {
                return "0";
            }
            return decimal.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException notDecimal) {
            return payload;
        }
    }

    /**
     * 按规范化形式比较两个载荷是否同值同参；任一为 null 时仅两者都为 null 才相等。
     */
    static boolean sameValue(String left, String right) {
        if (left == null || right == null) {
            return left == right;
        }
        return canonical(left).equals(canonical(right));
    }

    /**
     * 计算载荷规范化形式的 SHA-256 十六进制摘要，用于幂等指纹，避免指纹残留原文。
     */
    static String sha256(String canonicalPayload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
