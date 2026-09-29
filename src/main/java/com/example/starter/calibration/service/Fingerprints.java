package com.example.starter.calibration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 请求指纹工具：用操作者、操作类型、规范化参数集合及所有影响结果字段构造指纹。
 *
 * <p>规范化方式：字段名按字典序排序（TreeMap），集合按元素字典序排序后输出，
 * 经 Jackson 序列化为确定的 JSON 字节，再取 SHA-256 十六进制。
 * 不使用任何未转义分隔符手工拼接，字段值中的分隔字符不会造成碰撞。
 */
public final class Fingerprints {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

    private Fingerprints() {
    }

    /**
     * 构造规范化指纹。
     *
     * @param actor     操作者（可为 null，表示该操作不区分操作者）
     * @param operation 操作类型
     * @param fields    影响结果的字段；值支持 String/Number/Boolean/null/Map/List，
     *                  List 会先复制排序（要求元素可比较），Map 按键字典序规范化
     * @return 64 位小写十六进制 SHA-256
     */
    public static String of(String actor, String operation, Map<String, ?> fields) {
        TreeMap<String, Object> canonical = new TreeMap<>();
        canonical.put("actor", actor);
        canonical.put("operation", operation);
        TreeMap<String, Object> sortedFields = new TreeMap<>();
        if (fields != null) {
            fields.forEach((key, value) -> sortedFields.put(key, normalize(value)));
        }
        canonical.put("fields", sortedFields);
        return sha256(toJson(canonical));
    }

    @SuppressWarnings("unchecked")
    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> nested = new TreeMap<>();
            map.forEach((k, v) -> nested.put(String.valueOf(k), normalize(v)));
            return nested;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Fingerprints::normalize)
                    .sorted(Fingerprints::compareCanonical)
                    .toList();
        }
        return value;
    }

    private static int compareCanonical(Object a, Object b) {
        if (a instanceof Comparable && b.getClass().isInstance(a) && a.getClass().equals(b.getClass())) {
            return ((Comparable<Object>) a).compareTo(b);
        }
        return toJson(a).compareTo(toJson(b));
    }

    private static String toJson(Object value) {
        try {
            return CANONICAL_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("规范化指纹序列化失败", ex);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
