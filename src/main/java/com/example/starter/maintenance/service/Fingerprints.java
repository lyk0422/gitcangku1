package com.example.starter.maintenance.service;

import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等身份指纹：以结构化 JSON（键序固定）描述一次写操作的身份，
 * 含操作、操作者、资源及全部参数和期望版本，避免字符串拼接的分隔符碰撞。
 */
public final class Fingerprints {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fingerprints() {
    }

    /**
     * 生成结构化指纹。
     *
     * @param operation 操作类型（如 REGISTER_COMPONENT）
     * @param operator  操作者标识
     * @param resource  资源标识（如部件合成编号）
     * @param params    全部业务参数（含期望版本），按键名排序序列化
     */
    public static String of(String operation, String operator, String resource,
                            Map<String, ?> params) {
        Map<String, Object> identity = new TreeMap<>();
        identity.put("operation", operation);
        identity.put("operator", operator);
        identity.put("resource", resource);
        identity.put("params", new TreeMap<>(params));
        try {
            return MAPPER.writeValueAsString(identity);
        } catch (Exception e) {
            throw new IllegalStateException("幂等身份指纹序列化失败", e);
        }
    }
}
