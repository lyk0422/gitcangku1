package com.example.starter.container.dto;

/**
 * 逐件巡检快照视图（FAIL 巡检的不可变逐件记录）。
 *
 * @param inspectionId   所属巡检记录主键
 * @param containerId    容器业务键
 * @param evidenceKey    证物业务键
 * @param evidenceStatus 快照时证物状态
 * @param custodianId    快照时保管人
 * @param sealNo         快照时封条编号
 * @param snapshotNo     件次序号（自 1 开始）
 */
public record ContainerItemSnapshotView(
        long inspectionId,
        String containerId,
        String evidenceKey,
        String evidenceStatus,
        String custodianId,
        String sealNo,
        int snapshotNo) {
}
