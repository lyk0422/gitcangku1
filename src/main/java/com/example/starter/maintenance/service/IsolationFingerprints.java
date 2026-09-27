package com.example.starter.maintenance.service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 隔离相关写操作的幂等指纹：以规范化 JSON 序列化（Map 按键排序、集合为已排序数组），
 * 不使用未转义分隔符拼接；指纹包含操作者、操作类型、规范化对象集合及所有影响结果的字段。
 */
final class IsolationFingerprints {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private IsolationFingerprints() {
    }

    static String createIsolation(String operator, List<String> sortedLockPointIds) {
        return write(new TreeMap<>(Map.of(
                "operator", operator,
                "lockPointIds", sortedLockPointIds)));
    }

    static String batchCreateIsolation(String operator, List<IsolationTxService.NormalizedItem> items) {
        List<Map<String, Object>> normalized = items.stream()
                .map(item -> Map.<String, Object>of(
                        "equipmentId", item.equipmentId(),
                        "orderId", item.orderId(),
                        "lockPointIds", item.lockPointIds()))
                .toList();
        return write(new TreeMap<>(Map.of(
                "operator", operator,
                "orders", normalized)));
    }

    static String confirmLockPoints(String operator, String role, List<String> sortedPointIds) {
        return write(new TreeMap<>(Map.of(
                "operator", operator,
                "role", role,
                "pointIds", sortedPointIds)));
    }

    static String releaseIsolation(String operator, String reason, Map<String, String> sortedReleases) {
        return write(new TreeMap<>(Map.of(
                "operator", operator,
                "reason", reason,
                "releases", new TreeMap<>(sortedReleases))));
    }

    private static String write(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹生成失败", e);
        }
    }
}
