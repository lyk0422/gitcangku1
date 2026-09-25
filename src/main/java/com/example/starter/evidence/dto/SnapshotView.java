package com.example.starter.evidence.dto;

import com.example.starter.evidence.EvidenceStatus;

/**
 * 逐件不可变巡检快照视图。
 *
 * @param inspectionId   关联巡检记录 id
 * @param containerKey   容器业务键快照
 * @param evidenceKey    证物业务键快照
 * @param evidenceStatus 巡检时证物状态快照
 * @param custodianId    巡检时证物保管人快照
 */
public record SnapshotView(
        Long inspectionId,
        String containerKey,
        String evidenceKey,
        EvidenceStatus evidenceStatus,
        String custodianId) {
}
