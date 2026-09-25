package com.example.starter.container;

import com.example.starter.evidence.EvidenceStatus;

import java.time.LocalDateTime;

/**
 * 逐件巡检快照实体，对应 container_item_snapshot 表。
 * 仅 FAIL 巡检在同一事务内为每件装载证物写入一行，只追加、不可变。
 *
 * @param id             主键
 * @param inspectionId   所属容器巡检记录主键
 * @param containerId    所属容器业务键
 * @param evidenceKey    证物业务键
 * @param evidenceStatus 快照时证物状态（FAIL 时全部为 SEALED）
 * @param custodianId    快照时证物保管人
 * @param sealNo         快照时证物封条编号
 * @param snapshotNo     件次序号，按装载顺序自 1 开始
 * @param createdAt      快照写入时间（Asia/Shanghai）
 */
public record ContainerItemSnapshot(
        Long id,
        long inspectionId,
        String containerId,
        String evidenceKey,
        EvidenceStatus evidenceStatus,
        String custodianId,
        String sealNo,
        int snapshotNo,
        LocalDateTime createdAt) {
}
