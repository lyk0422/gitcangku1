package com.example.starter.exposure.exposure;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等请求身份指纹：以有序键值 Map 序列化为 JSON 文本，
 * 身份含操作（由调用方单独存储比较）、操作者、资源及全部参数和期望版本；
 * 结构化序列化避免分隔符拼接造成的碰撞（如 "a|b" 与 "a"、"b" 歧义）。
 */
public final class IdempotencyIdentity {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IdempotencyIdentity() {
    }

    /**
     * 生成结构化指纹。键按字典序排序，列表保持原有顺序（有序创意列表顺序敏感）。
     *
     * @param fields 参数字段（键非空；值为 null 时序列化为 JSON null）
     * @return 确定性 JSON 文本
     */
    public static String of(Map<String, ?> fields) {
        try {
            return MAPPER.writeValueAsString(new TreeMap<>(fields));
        } catch (Exception e) {
            throw new IllegalStateException("failed to build idempotency fingerprint", e);
        }
    }
}
