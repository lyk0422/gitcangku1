package com.example.starter.maintenance.service;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等指纹工具：操作、操作者、资源与全部参数（含期望版本）以 JSON 结构化序列化后比较，
 * 避免分隔符拼接造成的参数碰撞（如 "a|b"+"c" 与 "a"+"b|c"）。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String of(ObjectMapper objectMapper, String operation, String operator,
                            String resource, Map<String, ?> params) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", operation);
        identity.put("operator", operator);
        identity.put("resource", resource);
        identity.put("params", params);
        try {
            return objectMapper.writeValueAsString(identity);
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }

    /** 按给定顺序构造参数表（键值成对）。 */
    public static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}