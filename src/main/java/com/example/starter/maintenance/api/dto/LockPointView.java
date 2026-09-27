package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 锁定点视图（创建时固化，解除后补充解除信息）。
 *
 * @param lockPointId    锁定点业务标识
 * @param label          锁定点名称；创建时未提供为 null
 * @param seqNo          创建顺序号，从 1 开始
 * @param releaseReason  该锁定点解除原因；未解除为 null
 * @param lockedAt       锁定登记时刻（UTC）
 * @param releasedAt     解除时刻（UTC）；未解除为 null
 * @param releasedBy     解除操作人；未解除为 null
 */
public record LockPointView(
        String lockPointId,
        String label,
        int seqNo,
        String releaseReason,
        Instant lockedAt,
        Instant releasedAt,
        String releasedBy) {
}
