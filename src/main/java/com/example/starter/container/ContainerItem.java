package com.example.starter.container;

import java.time.LocalDateTime;

/**
 * 容器装载关系实体，对应 container_item 表。一件证物同一时刻至多装入一个容器。
 *
 * @param id          主键
 * @param containerId 所属容器业务键
 * @param evidenceKey 装载证物业务键
 * @param loadedAt    装载时间（Asia/Shanghai）
 */
public record ContainerItem(
        Long id,
        String containerId,
        String evidenceKey,
        LocalDateTime loadedAt) {
}
