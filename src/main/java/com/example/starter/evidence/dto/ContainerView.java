package com.example.starter.evidence.dto;

import com.example.starter.evidence.ContainerStatus;

import java.time.LocalDateTime;

/**
 * 容器视图。
 *
 * @param containerKey 容器业务键
 * @param location     容器存放位置描述
 * @param custodianId  容器登记保管人
 * @param sealVersion  当前封条版本号
 * @param status       容器状态
 * @param createdAt    建容器时间（Asia/Shanghai）
 * @param updatedAt    最近换封条或状态变更时间（Asia/Shanghai）
 */
public record ContainerView(
        String containerKey,
        String location,
        String custodianId,
        Integer sealVersion,
        ContainerStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
