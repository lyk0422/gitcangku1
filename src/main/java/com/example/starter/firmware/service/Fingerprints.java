package com.example.starter.firmware.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 幂等指纹工具：以规范化 JSON 序列化请求字段，集合作为 JSON 数组编码，
 * 不通过未转义分隔符拼接，避免字段值中的分隔符造成指纹碰撞。
 * 调用方负责传入全部影响结果的字段（含操作者、操作类型），集合须先规范化排序。
 */
public final class Fingerprints {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fingerprints() {
    }

    /**
     * 以键值对构建规范化指纹：{"k1":v1,"k2":v2,...}。参数个数必须为偶数，键为字符串。
     */
    public static String of(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("指纹键值对个数必须为偶数");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            fields.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }
}
