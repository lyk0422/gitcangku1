package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 封存箱（容器）实体，对应 container 表。
 * containerKey/sealNo 创建后不可修改；custodianId 随整箱交接与子树原子切换；
 * version 从 1 开始，成员变更携带根箱 expectedVersion 校验并加一。
 *
 * @param id           主键
 * @param containerKey 容器业务键，全局唯一
 * @param sealNo       箱独立封条编号
 * @param custodianId  当前保管人
 * @param status       交接状态（SEALED / TRANSFER_PENDING）
 * @param sealStatus   箱独立封条状态（INTACT / BROKEN）
 * @param version      版本，从 1 开始递增
 * @param createdAt    创建时间（Asia/Shanghai）
 * @param updatedAt    最近一次变更时间（Asia/Shanghai）
 */
public record Container(
        Long id,
        String containerKey,
        String sealNo,
        String custodianId,
        ContainerStatus status,
        SealStatus sealStatus,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
