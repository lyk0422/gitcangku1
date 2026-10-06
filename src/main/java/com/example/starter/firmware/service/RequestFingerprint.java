package com.example.starter.firmware.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 幂等请求指纹：以固定键序的结构化 JSON 表示操作身份（操作、操作者、资源、全部参数与期望版本），
 * 避免字符串拼接的分隔符碰撞（如参数值本身含有分隔符）。
 */
public final class RequestFingerprint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestFingerprint() {
    }

    /**
     * 按 (键, 值) 交替传入构建指纹，键为 String，值为任意可 JSON 序列化对象（null 保留）。
     */
    public static String of(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("指纹键值必须成对出现");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            fields.put((String) keyValues[i], keyValues[i + 1]);
        }
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }
}
