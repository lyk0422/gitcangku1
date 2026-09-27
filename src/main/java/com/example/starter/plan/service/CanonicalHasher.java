package com.example.starter.plan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 幂等指纹工具：以操作者、操作类型、规范化集合及全部影响结果字段构造规范化 JSON 后取 SHA-256。
 *
 * <p>不使用未转义分隔符手工拼接：对象一律用 {@link TreeMap}（键字典序），
 * 集合一律先排序（{@link SortedSet} 或显式排序的列表），再由开启键排序的
 * {@link ObjectMapper} 序列化，杜绝字段顺序与分隔符注入歧义。
 */
@Component
public class CanonicalHasher {

    private final ObjectMapper canonicalMapper;

    public CanonicalHasher(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /**
     * 对任意已规范化（映射键可比较、集合已排序）的对象计算 SHA-256 十六进制指纹。
     */
    public String sha256(Object canonical) {
        try {
            byte[] body = canonicalMapper.writeValueAsBytes(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(body);
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("规范化指纹序列化失败", e);
        }
    }

    /**
     * 新建字典序键的规范化对象。
     */
    public TreeMap<String, Object> object() {
        return new TreeMap<>();
    }

    /**
     * 构造键值对形式的规范化对象，键按字典序排列。
     */
    public TreeMap<String, Object> object(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues 必须成对出现");
        }
        TreeMap<String, Object> map = new TreeMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * 将元素为标量的集合规范化为字典序字符串列表（null 安全）。
     */
    public List<String> sortedStrings(SortedSet<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * 规范化字符串到字符串集合的映射：键字典序、值排序。
     */
    public TreeMap<String, List<String>> sortedMapOfSets(Map<String, SortedSet<String>> source) {
        TreeMap<String, List<String>> result = new TreeMap<>();
        new TreeMap<>(source).forEach((k, v) -> result.put(k, List.copyOf(v)));
        return result;
    }

    /** UTF-8 常量，便于调用方保持一致编码。 */
    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
