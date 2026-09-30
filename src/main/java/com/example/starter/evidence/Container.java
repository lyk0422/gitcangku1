package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 容器实体，对应 container 表。
 *
 * @param id           主键
 * @param containerKey 容器业务键，全局唯一
 * @param location     容器存放位置描述（库房/货架编号）
 * @param custodianId  容器登记保管人（建容器操作人）
 * @param sealVersion  当前封条版本号，初始 1，每换一次封条递增 1
 * @param status       容器状态
 * @param createdAt    建容器时间（Asia/Shanghai）
 * @param updatedAt    最近换封条或状态变更时间（Asia/Shanghai）
 */
public record Container(
        Long id,
        String containerKey,
        String location,
        String custodianId,
        Integer sealVersion,
        ContainerStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
