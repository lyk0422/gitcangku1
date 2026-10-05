package com.example.starter.maintenance.service;

import java.util.Arrays;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等请求身份指纹：将操作、操作者、资源及全部参数和期望版本按固定顺序组成 JSON 数组，
 * 结构化比较，避免字符串拼接（如分隔符 "|"）在参数含分隔符时发生碰撞。
 */
public final class Fingerprints {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fingerprints() {
    }

    /**
     * 生成结构化指纹。parts 按调用方约定的固定顺序传入；null 值原样序列化，
     * 与空字符串、其它位置的非空值均可区分。
     */
    public static String of(Object... parts) {
        try {
            return MAPPER.writeValueAsString(Arrays.asList(parts));
        } catch (Exception e) {
            throw new IllegalStateException("请求指纹序列化失败", e);
        }
    }
}
