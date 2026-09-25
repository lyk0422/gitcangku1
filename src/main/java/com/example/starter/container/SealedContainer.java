package com.example.starter.container;

import java.time.LocalDateTime;

/**
 * 封存容器实体，对应 sealed_container 表。
 *
 * @param id                主键
 * @param containerId       容器业务键，全局唯一
 * @param status            容器状态
 * @param nextInspectionAt  下次巡检截止时刻（UTC）；允许提前巡检，新值必须严格晚于实际巡检时刻
 * @param version           容器行版本，任何容器变更自增
 * @param createdAt         创建时间（Asia/Shanghai）
 * @param updatedAt         最近一次变更时间（Asia/Shanghai）
 */
public record SealedContainer(
        Long id,
        String containerId,
        ContainerStatus status,
        LocalDateTime nextInspectionAt,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
