package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 能源隔离令。
 *
 * @param isolationId    隔离令标识
 * @param equipmentId    所属设备标识
 * @param state          状态：PENDING / ACTIVE / RELEASED
 * @param createdBy      创建操作人
 * @param createdAt      创建时刻（UTC）
 * @param effectiveAt    生效时刻（UTC）：第二名角色确认时刻；PENDING 时为 null
 * @param releasedAt     解除时刻（UTC）：RELEASED 时有值，否则 null
 * @param releaseReason  解除原因；未解除时为 null
 * @param releasedBy     解除登记操作人；未解除时为 null
 */
public record IsolationOrder(
        long isolationId,
        String equipmentId,
        String state,
        String createdBy,
        Instant createdAt,
        Instant effectiveAt,
        Instant releasedAt,
        String releaseReason,
        String releasedBy) {
}
