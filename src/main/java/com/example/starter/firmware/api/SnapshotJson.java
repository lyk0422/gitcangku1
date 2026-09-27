package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ModuleSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 任务行设备模块快照 JSON 的读写工具，供任务视图复用，保证历史字段的 null/空数组语义稳定。
 */
final class SnapshotJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ModuleSnapshot>> ITEMS = new TypeReference<>() {
    };

    private SnapshotJson() {
    }

    static String write(List<ModuleSnapshot> snapshots) {
        try {
            return MAPPER.writeValueAsString(snapshots);
        } catch (Exception e) {
            throw new IllegalStateException("设备模块快照序列化失败", e);
        }
    }

    static List<DeviceModulesView.Item> parseItems(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, ITEMS).stream().map(DeviceModulesView.Item::of).toList();
        } catch (Exception e) {
            throw new IllegalStateException("设备模块快照反序列化失败", e);
        }
    }
}
