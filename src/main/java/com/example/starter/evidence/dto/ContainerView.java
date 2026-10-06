package com.example.starter.evidence.dto;

import com.example.starter.evidence.ContainerStatus;
import com.example.starter.evidence.SealStatus;

import java.time.LocalDateTime;

/**
 * 封存箱视图。
 *
 * @param containerKey 容器业务键
 * @param sealNo       箱独立封条编号
 * @param custodianId  当前保管人
 * @param status       交接状态（SEALED / TRANSFER_PENDING）
 * @param sealStatus   箱独立封条状态（INTACT / BROKEN）
 * @param version      版本，从 1 开始递增
 * @param createdAt    创建时间（Asia/Shanghai）
 * @param updatedAt    最近一次变更时间（Asia/Shanghai）
 */
public record ContainerView(
        String containerKey,
        String sealNo,
        String custodianId,
        ContainerStatus status,
        SealStatus sealStatus,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
