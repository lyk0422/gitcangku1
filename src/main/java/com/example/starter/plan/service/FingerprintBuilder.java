package com.example.starter.plan.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹构建器：字段一律按「长度:值」前缀编码追加，null 以独立标记表示，
 * 集合逐元素追加并显式收尾，不依赖任何未转义分隔符拼接，杜绝字段内容注入导致的指纹碰撞。
 */
final class FingerprintBuilder {

    private final StringBuilder sb = new StringBuilder();

    FingerprintBuilder(String opType) {
        field(opType);
    }

    /**
     * 追加一个字段；null 编码为独立标记 "-"，非空编码为 "长度:值"。
     */
    FingerprintBuilder field(String value) {
        if (value == null) {
            sb.append('-');
        } else {
            sb.append(value.length()).append(':').append(value);
        }
        sb.append('\n');
        return this;
    }

    String sha256() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
