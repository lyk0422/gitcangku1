package com.example.starter.batch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 幂等指纹工具：对业务参数做 SHA-256 摘要。
 * 拼接采用长度前缀编码（"长度:内容"），不依赖任何分隔符，
 * 因此参数包含任意字符都不会产生拼接碰撞。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 对给定参数序列计算指纹；null 以 -1 长度编码，与空串区分。
     */
    public static String of(List<String> parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            if (part == null) {
                canonical.append("-1:");
            } else {
                canonical.append(part.length()).append(':').append(part);
            }
        }
        return sha256(canonical.toString());
    }

    private static String sha256(String canonical) {
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