package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 逐件不可变巡检快照实体，对应 container_inspection_snapshot 表。
 * FAIL 巡检事务内对容器内每件证物写一行，整单回滚时快照同样不写入。
 *
 * @param id             主键
 * @param inspectionId   关联容器巡检记录 id
 * @param containerKey   容器业务键快照
 * @param evidenceKey    被巡检证物业务键快照
 * @param evidenceStatus 巡检时证物状态快照
 * @param custodianId    巡检时证物保管人快照
 * @param createdAt      快照写入时间（Asia/Shanghai）
 */
public record ContainerInspectionSnapshot(
        Long id,
        Long inspectionId,
        String containerKey,
        String evidenceKey,
        EvidenceStatus evidenceStatus,
        String custodianId,
        LocalDateTime createdAt) {
}
