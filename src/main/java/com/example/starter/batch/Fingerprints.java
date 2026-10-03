package com.example.starter.batch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 幂等指纹工具：将操作类型、操作者、规范化对象集合及所有影响结果的字段
 * 按长度前缀编码（"长度:值" 逐段拼接）后取 SHA-256。
 * 长度前缀编码不依赖任何分隔符，字段值含任意字符也不会产生拼接碰撞。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 对给定字段序列计算规范指纹；字段不允许为 null，调用方先做非空校验。
     */
    public static String of(String... parts) {
        return of(List.of(parts));
    }

    /**
     * 对给定字段列表计算规范指纹；集合元素逐个长度前缀编码，顺序由调用方决定。
     */
    public static String of(List<String> parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            if (part == null) {
                throw new IllegalArgumentException("指纹字段不允许为 null");
            }
            canonical.append(part.length()).append(':').append(part);
        }
        return sha256Hex(canonical.toString());
    }

    private static String sha256Hex(String canonical) {
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