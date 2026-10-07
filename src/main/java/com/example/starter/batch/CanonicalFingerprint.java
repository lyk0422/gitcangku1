package com.example.starter.batch;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构化参数指纹：按字段名逐个登记参数，编码为 字段名=值长度:值 的规范串后取 SHA-256。
 * 长度前缀保证字段无碰撞（参数内容任意也不会与字段边界混淆）；
 * 十进制按数值规范化（1.0 与 1.00 同值同参），文本原样比较。
 */
public final class CanonicalFingerprint {

    private final List<String> parts = new ArrayList<>();

    /**
     * 登记文本参数，原样参与比较。
     */
    public CanonicalFingerprint put(String name, String value) {
        return putRaw(name, value == null ? "null" : value);
    }

    /**
     * 登记整数参数。
     */
    public CanonicalFingerprint put(String name, long value) {
        return putRaw(name, Long.toString(value));
    }

    /**
     * 登记十进制参数：按数值规范化（去尾零），同值不同写法的十进制得到同一指纹。
     */
    public CanonicalFingerprint put(String name, BigDecimal value) {
        return putRaw(name, value == null ? "null" : value.stripTrailingZeros().toPlainString());
    }

    /**
     * 登记 UTC instant 参数，ISO-8601 字符串。
     */
    public CanonicalFingerprint put(String name, Instant value) {
        return putRaw(name, value == null ? "null" : value.toString());
    }

    private CanonicalFingerprint putRaw(String name, String canonicalValue) {
        parts.add(name + "=" + canonicalValue.length() + ":" + canonicalValue);
        return this;
    }

    /**
     * 计算规范串的 SHA-256 十六进制摘要。
     */
    public String sha256() {
        String canonical = String.join(";", parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
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
