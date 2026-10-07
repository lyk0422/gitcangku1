package com.example.starter.observation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 请求指纹：以全局 requestId 结构化比较操作、资源、全参数及期望版本；
 * 十进制按值、文本原样，调用方负责在传入前完成规范化（如圆弧集合排序）。
 */
final class Fingerprints {

    private static final String SEPARATOR = "\u0001";

    private Fingerprints() {
    }

    /**
     * 计算操作与参数的 SHA-256 指纹；null 参数以固定占位串参与计算。
     */
    static String of(String operation, String... parts) {
        StringBuilder raw = new StringBuilder(operation);
        for (String part : parts) {
            raw.append(SEPARATOR).append(part == null ? "<null>" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
