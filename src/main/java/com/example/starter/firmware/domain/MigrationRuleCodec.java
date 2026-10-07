package com.example.starter.firmware.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 迁移规则列表 JSON 编解码：数组顺序即执行顺序，序列化文本稳定，可用于幂等指纹与发布单存储。
 */
public final class MigrationRuleCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<MigrationRule>> LIST_TYPE = new TypeReference<>() {
    };

    private MigrationRuleCodec() {
    }

    /**
     * 规则列表序列化为 JSON 数组文本，顺序保持不变。
     */
    public static String toJson(List<MigrationRule> rules) {
        try {
            return MAPPER.writeValueAsString(rules);
        } catch (Exception e) {
            throw new IllegalStateException("迁移规则序列化失败", e);
        }
    }

    /**
     * JSON 数组文本还原为有序规则列表；null 或空白视为无规则。
     */
    public static List<MigrationRule> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("迁移规则反序列化失败", e);
        }
    }
}
