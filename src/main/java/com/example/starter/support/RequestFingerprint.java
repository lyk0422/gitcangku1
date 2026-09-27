package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 请求幂等指纹：将操作者、操作类型、规范化对象集合及所有影响结果的字段
 * 以长度前缀编码拼接后取 SHA-256。
 *
 * <p>每个字段编码为 {@code 长度:内容}，字段边界由长度唯一确定，
 * 不依赖任何分隔符转义，集合元素逐一作为独立字段编码。
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    /** 计算规范化字段序列的指纹。null 字段按空串处理。 */
    public static String of(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "" : part;
            sb.append(value.length()).append(':').append(value);
        }
        return sha256(sb.toString());
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
