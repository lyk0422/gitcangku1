package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * calcKey 指纹派生：对操作类型与全部规范化输入做 SHA-256。
 * 指纹含测量/批次版本、环境、系数版本与全部输入，任一不同即得到不同键。
 */
final class Fingerprints {

    private Fingerprints() {
    }

    static String of(String operation, Object... parts) {
        StringBuilder sb = new StringBuilder(operation);
        for (Object part : parts) {
            sb.append('|').append(normalize(part));
        }
        return sha256(sb.toString());
    }

    private static String normalize(Object part) {
        if (part == null) {
            return "\u0000";
        }
        if (part instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (part instanceof Object[] array) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(normalize(array[i]));
            }
            return sb.append(']').toString();
        }
        if (part instanceof List<?> list) {
            return list.toString();
        }
        return part.toString();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
