package com.example.starter.firmware.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;

/**
 * 幂等指纹：将操作、资源与全部参数序列化为 JSON 数组做结构化比较，
 * 避免字符串拼接的分隔符碰撞（参数本身可含任意分隔符）。
 */
public final class Fingerprint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fingerprint() {
    }

    public static String of(Object... values) {
        try {
            return MAPPER.writeValueAsString(Arrays.asList(values));
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }
}