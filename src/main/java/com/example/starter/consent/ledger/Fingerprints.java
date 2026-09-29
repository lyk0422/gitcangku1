package com.example.starter.consent.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 规范化指纹工具：对象集合先去重并按字典序排序，再用规范化 JSON 序列化后做 SHA-256。
 *
 * <p>不直接以分隔符拼接字符串：所有字段通过 JSON 结构表达，键排序固定，
 * 特殊字符由 JSON 转义，避免“a/b + c”与“a + b/c”碰撞。
 */
public final class Fingerprints {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Fingerprints() {
    }

    /**
     * 规范化对象集合：去重后按字典序升序。
     */
    public static List<String> normalizeObjects(List<String> objects) {
        return objects.stream().distinct().sorted().toList();
    }

    /**
     * 对象规范集合摘要：SHA-256 十六进制（64 字符）。
     */
    public static String digestObjects(List<String> normalizedObjects) {
        return sha256(canonical(List.copyOf(normalizedObjects)));
    }

    /**
     * 任意字段集合的指纹：字段名按字典序排列后做规范化 JSON 再 SHA-256。
     */
    public static String of(Map<String, Object> fields) {
        return sha256(canonical(new TreeMap<>(fields)));
    }

    /**
     * 对任意规范化字符串计算 SHA-256 十六进制摘要（64 字符）。
     */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 便捷构造可变字段表。
     */
    public static Map<String, Object> fields(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("字段必须为键值对");
        }
        Map<String, Object> map = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static String canonical(Object value) {
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规范化序列化失败", e);
        }
    }
}
