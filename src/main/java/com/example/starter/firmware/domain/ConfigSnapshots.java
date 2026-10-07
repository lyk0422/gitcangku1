package com.example.starter.firmware.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.TreeMap;

/**
 * 配置快照 JSON 编解码：序列化前按键排序，保证同一配置快照文本稳定，可用于幂等指纹与冻结存储。
 */
public final class ConfigSnapshots {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Integer>> MAP_TYPE = new TypeReference<>() {
    };

    private ConfigSnapshots() {
    }

    /**
     * 配置序列化为按键排序的 JSON 文本。
     */
    public static String toJson(Map<String, Integer> config) {
        try {
            return MAPPER.writeValueAsString(new TreeMap<>(config));
        } catch (Exception e) {
            throw new IllegalStateException("配置快照序列化失败", e);
        }
    }

    /**
     * 快照 JSON 还原为按键排序的配置；null 或空白视为空配置。
     */
    public static Map<String, Integer> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new TreeMap<>(MAPPER.readValue(json, MAP_TYPE));
        } catch (Exception e) {
            throw new IllegalStateException("配置快照反序列化失败", e);
        }
    }
}
