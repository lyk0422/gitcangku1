package com.example.starter.firmware.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;

/**
 * 幂等请求指纹：以 JSON 数组结构化序列化全部身份要素（操作、操作者、资源、全部参数与期望版本），
 * 元素逐个转义，避免字符串拼接的分隔符碰撞。
 */
final class Fingerprints {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fingerprints() {
    }

    static String of(Object... parts) {
        try {
            return MAPPER.writeValueAsString(Arrays.asList(parts));
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }
}
