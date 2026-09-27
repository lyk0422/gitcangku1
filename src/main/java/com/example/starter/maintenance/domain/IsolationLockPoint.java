package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 隔离令锁定点（创建时固化，解除时逐一登记解除原因与解除人）。
 *
 * @param id             行主键
 * @param isolationId    所属隔离令标识
 * @param lockPointId    锁定点业务标识，隔离令内唯一
 * @param label          锁定点名称（可空，null 表示调用方未提供）
 * @param seqNo          创建时的顺序号，从 1 开始，用于稳定排序
 * @param releaseReason  该锁定点的解除原因；未解除为 null
 * @param lockedAt       锁定登记时刻（UTC，随隔离令创建）
 * @param releasedAt     解除时刻（UTC）；未解除为 null
 * @param releasedBy     解除操作人；未解除为 null
 */
public record IsolationLockPoint(
        long id,
        long isolationId,
        String lockPointId,
        String label,
        int seqNo,
        String releaseReason,
        Instant lockedAt,
        Instant releasedAt,
        String releasedBy) {
}
