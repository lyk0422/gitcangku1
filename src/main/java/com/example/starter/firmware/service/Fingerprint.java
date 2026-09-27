package com.example.starter.firmware.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 幂等指纹构造：统一输出结构化 JSON，禁止未转义分隔符手工拼接。
 * 根节点固定包含操作类型 api、操作者 operator（无操作者的设备侧接口为 null）与参数载荷 payload；
 * 载荷中的集合必须为已规范化、排序后的不可变列表，保证同参同指纹、异参异指纹。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String of(ObjectMapper objectMapper, String api, String operator, Map<String, ?> payload) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("api", api);
        root.put("operator", operator);
        root.put("payload", payload);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }

    /**
     * 保持插入顺序的参数载荷构造器，字段名即结果相关参数名。
     */
    public static Map<String, Object> payload(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("指纹参数必须为键值对");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            map.put((String) keyValues[i], value instanceof List<?> list ? List.copyOf(list) : value);
        }
        return map;
    }
}
