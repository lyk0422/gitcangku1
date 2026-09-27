package com.example.starter.plan.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;

/**
 * 幂等指纹构建器：以长度前缀编码逐字段追加（"长度:内容"），
 * 集合先写元素个数再逐项编码，不使用未转义分隔符拼接，避免字段内容注入歧义。
 * null 统一编码为 "-1:"，与空串（"0:"）区分。
 */
final class Fingerprint {

    private final StringBuilder sb = new StringBuilder();

    private Fingerprint(String opType) {
        field(opType);
    }

    static Fingerprint of(String opType) {
        return new Fingerprint(opType);
    }

    Fingerprint field(String value) {
        if (value == null) {
            sb.append("-1:");
        } else {
            sb.append(value.length()).append(':').append(value);
        }
        return this;
    }

    Fingerprint field(long value) {
        return field(Long.toString(value));
    }

    /**
     * 追加一个字符串集合：先写元素个数，再逐项长度前缀编码。
     * 调用方负责按业务语义决定是否先排序（集合语义须先规范化排序）。
     */
    Fingerprint fields(Collection<String> values) {
        sb.append(values.size()).append('#');
        for (String v : values) {
            field(v);
        }
        return this;
    }

    /**
     * 返回已追加内容的原始编码（不做摘要），用于把一条复合记录作为
     * 上层字段嵌入；外层长度前缀可完整包裹，分隔符无歧义。
     */
    String encode() {
        return sb.toString();
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
