package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等请求参数指纹工具。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算参数序列的 SHA-256 十六进制指纹。
     * 采用“长度:内容”前缀编码，避免简单分隔符拼接在内容含分隔符时产生碰撞。
     */
    public static String sha256(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "" : part;
            joined.append(value.length()).append(':').append(value).append('|');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
