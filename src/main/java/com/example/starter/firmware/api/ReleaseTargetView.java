package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单目标设备快照诊断视图（只读）。
 *
 * @param enabled        是否启用目标标签策略；false 时按型号动态匹配，其余策略字段为 null
 * @param tagDictVersion 快照解析时固化的标签字典版本；未启用为 null
 * @param resolvedAtUtc  快照解析时刻（UTC ISO-8601）；未启用为 null
 * @param expression     固化的规范化标签表达式；未启用为 null
 * @param targetSize     快照成员数（实际值）；未启用为 null
 * @param deviceIds      规范排序（device_id 字典序）的快照成员，稳定排序
 */
public record ReleaseTargetView(boolean enabled, Long tagDictVersion, String resolvedAtUtc,
                                TargetExpressionView expression, Integer targetSize, List<String> deviceIds) {
}
