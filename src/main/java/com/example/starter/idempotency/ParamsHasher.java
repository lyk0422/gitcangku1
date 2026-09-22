package com.example.starter.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * 归一化业务参数指纹：对键排序后拼接，再取 SHA-256，
 * 保证字段顺序不同但内容相同的请求指纹一致。
 */
public final class ParamsHasher {

    private ParamsHasher() {
    }

    /** 计算 SHA-256 十六进制指纹。 */
    public static String sha256(OperationType operation, Map<String, ?> params) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        if (params != null) {
            sorted.putAll(params);
        }
        StringBuilder canonical = new StringBuilder(operation.name());
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            canonical.append('|').append(entry.getKey()).append('=').append(entry.getValue());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
