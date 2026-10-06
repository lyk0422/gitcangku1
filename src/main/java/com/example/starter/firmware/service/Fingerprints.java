package com.example.starter.firmware.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

/**
 * 幂等请求指纹：以 JSON 数组结构化序列化操作、资源与全部参数，
 * 由 JSON 转义保证任意参数内容都不会产生分隔符碰撞。
 */
final class Fingerprints {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fingerprints() {
    }

    static String of(Object... parts) {
        try {
            List<Object> identity = Arrays.asList(parts);
            return MAPPER.writeValueAsString(identity);
        } catch (Exception e) {
            throw new IllegalStateException("请求指纹序列化失败", e);
        }
    }
}
