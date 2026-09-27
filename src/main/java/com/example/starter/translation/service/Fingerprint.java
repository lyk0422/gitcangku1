package com.example.starter.translation.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作指纹：对操作者、操作类型与全部结果相关字段做 SHA-256。
 * 规范化集合（目标语言、占位符集合等）必须以排序后的 List 传入，由 JSON 序列化承载结构，
 * 不能直接以未转义的分隔符字符串拼接，避免集合元素内含分隔符导致指纹碰撞。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * 计算指纹。
     *
     * @param mapper       与应用一致的 JSON 映射器
     * @param operationType 操作类型
     * @param actor        操作者 X-Actor-Id；无操作者时为 null（序列化为 JSON null）
     * @param resultFields 固定插入顺序的结果相关字段；集合字段须为规范化后的排序列表
     * @return 64 位小写十六进制 SHA-256
     */
    public static String of(ObjectMapper mapper, String operationType, String actor,
                            Map<String, ?> resultFields) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("operationType", operationType);
        canonical.put("actor", actor);
        canonical.put("result", resultFields);
        try {
            byte[] bytes = mapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }

    /** 便捷构造固定键序的结果字段容器。 */
    public static Map<String, Object> ordered(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("键值必须成对出现");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** 规范化集合的类型标注，便于阅读；实际就是排序后的不可变列表。 */
    public static List<String> collection(List<String> canonicalSorted) {
        return List.copyOf(canonicalSorted);
    }
}
