package com.example.starter.firmware.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化幂等指纹：身份包含操作、操作者、资源及全部参数（含期望版本），
 * 以 JSON 序列化做结构化比较，避免字符串拼接的分隔符碰撞。
 */
public final class Fingerprints {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fingerprints() {
    }

    /**
     * 生成指纹。params 为有序参数表，键为参数名，值为参数原值（null 参与比较）。
     */
    public static String of(String operation, String operator, String resource, Map<String, ?> params) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", operation);
        identity.put("operator", operator);
        identity.put("resource", resource);
        identity.put("params", params == null ? Map.of() : new LinkedHashMap<>(params));
        try {
            return MAPPER.writeValueAsString(identity);
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }
}
