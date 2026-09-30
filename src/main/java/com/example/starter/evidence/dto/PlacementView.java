package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 证物位置轨迹视图。历史轨迹不随复核或后续移动改写。
 *
 * @param evidenceKey  关联证物业务键
 * @param containerKey 所在容器业务键
 * @param location     放入时容器位置快照
 * @param placedAt     放入时刻（Asia/Shanghai）
 * @param removedAt    移出时刻（Asia/Shanghai）；null 表示仍在箱内
 */
public record PlacementView(
        String evidenceKey,
        String containerKey,
        String location,
        LocalDateTime placedAt,
        LocalDateTime removedAt) {
}
