package com.example.starter.container.dto;

import java.time.LocalDateTime;

/**
 * 封存容器视图。
 *
 * @param containerId      容器业务键
 * @param status           容器状态
 * @param nextInspectionAt 下次巡检截止时刻（UTC）
 * @param version          容器行版本
 * @param createdAt        创建时间（Asia/Shanghai）
 * @param updatedAt        最近一次变更时间（Asia/Shanghai）
 */
public record ContainerView(
        String containerId,
        String status,
        LocalDateTime nextInspectionAt,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
