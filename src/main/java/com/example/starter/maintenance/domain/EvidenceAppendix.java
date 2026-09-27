package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 完工后更正附录（只追加，不改变周期与工时历史）。
 *
 * @param id           自增主键
 * @param equipmentId  所属设备标识
 * @param cycleNo      关联的已完成保养周期序号
 * @param operatorId   创建附录的操作人员
 * @param note         更正说明
 * @param createdAt    附录创建时刻（UTC）
 */
public record EvidenceAppendix(
        long id,
        String equipmentId,
        int cycleNo,
        String operatorId,
        String note,
        Instant createdAt) {
}
