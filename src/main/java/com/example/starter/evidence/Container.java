package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 封存箱实体，对应 evidence_container 表。
 * containerKey/sealNo 创建后不可修改；custodianId 与 status 随整树交接流转；
 * version 仅根箱有意义，从 1 开始，成员变更携带 expectedVersion 并加一。
 *
 * @param id           主键
 * @param containerKey 封存箱业务键，全局唯一
 * @param sealNo       箱独立封条编号
 * @param custodianId  当前保管人
 * @param status       箱状态
 * @param version      成员版本（根箱从 1 开始递增）
 * @param parentKey    直接父箱业务键；NULL 表示根箱
 * @param rootKey      所属根箱业务键
 * @param createdAt    创建时间（Asia/Shanghai）
 * @param updatedAt    最近一次变更时间（Asia/Shanghai）
 */
public record Container(
        Long id,
        String containerKey,
        String sealNo,
        String custodianId,
        ContainerStatus status,
        int version,
        String parentKey,
        String rootKey,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
