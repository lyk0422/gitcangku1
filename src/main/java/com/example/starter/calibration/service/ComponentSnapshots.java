package com.example.starter.calibration.service;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.example.starter.calibration.api.dto.UncertaintyComponentView;

/**
 * 分量快照 JSON 序列化工具：预算修订与放行记录以 JSON 数组固化分量集合。
 */
final class ComponentSnapshots {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<UncertaintyComponentView>> COMPONENT_LIST =
            new TypeReference<>() {
            };

    private ComponentSnapshots() {
    }

    /**
     * 将分量视图列表序列化为 JSON 数组字符串（调用方保证已按名称排序）。
     */
    static String toJson(List<UncertaintyComponentView> components) {
        try {
            return MAPPER.writeValueAsString(components);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("分量快照序列化失败", ex);
        }
    }

    /**
     * 将快照 JSON 解析回分量视图列表。
     */
    static List<UncertaintyComponentView> fromJson(String json) {
        try {
            return MAPPER.readValue(json, COMPONENT_LIST);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("分量快照解析失败", ex);
        }
    }
}
