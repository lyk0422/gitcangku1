package com.example.starter.batch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹工具：以 NUL 为分隔符拼接规范化字段后取 SHA-256。
 * 业务参数不可能包含 NUL，集合元素逐个作为独立分段拼接，
 * 不通过未转义分隔符拼接集合，避免拼接碰撞。
 */
public final class Fingerprints {

    /**
     * 指纹拼接分隔符（NUL，八进制转义 \000）：业务参数不可能包含该字符。
     */
    private static final String SEP = "\000";

    private Fingerprints() {
    }

    /**
     * 对给定分段计算 SHA-256 十六进制摘要；每段为独立字段或集合的单个元素。
     */
    public static String of(String... parts) {
        String canonical = String.join(SEP, parts);
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
