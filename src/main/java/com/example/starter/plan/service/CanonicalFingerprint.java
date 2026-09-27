package com.example.starter.plan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等指纹工具：对操作者、操作类型、规范化集合及全部影响结果的字段构造规范化 JSON，
 * 再取 SHA-256；不使用未转义分隔符拼接，避免字段内容破坏指纹唯一性。
 */
public final class CanonicalFingerprint {

    private CanonicalFingerprint() {
    }

    /**
     * 将对象规范化为 JSON 字符串：Map 键递归字典序排列；List 保持调用方给定顺序
     * （集合语义的入参须由调用方先排序/去重）。
     */
    public static String canonical(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(normalize(value));
        } catch (Exception e) {
            throw new IllegalStateException("规范化指纹序列化失败", e);
        }
    }

    /**
     * 规范化 JSON 的 SHA-256（十六进制小写）。
     */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
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

    /**
     * 便捷构造有序 Map（保持字典序输出）。
     */
    public static Map<String, Object> orderedMap() {
        return new TreeMap<>();
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), normalize(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(CanonicalFingerprint::normalize).toList();
        }
        return value;
    }
}
