package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 能源隔离令。
 *
 * @param orderId            隔离令标识
 * @param equipmentId        所属设备标识
 * @param status             令状态：PENDING 待确认 / EFFECTIVE 已生效 / RELEASED 已解除
 * @param createdBy          创建操作人
 * @param createdAt          创建时刻（UTC）
 * @param effectiveAt        生效时刻（UTC），未生效为 null
 * @param releasedAt         解除时刻（UTC），未解除为 null
 * @param releasedBy         解除操作人，未解除为 null
 * @param releaseReason      解除原因，未解除为 null
 */
public record IsolationOrder(
        long orderId,
        String equipmentId,
        String status,
        String createdBy,
        Instant createdAt,
        Instant effectiveAt,
        Instant releasedAt,
        String releasedBy,
        String releaseReason) {

    public static final String PENDING = "PENDING";
    public static final String EFFECTIVE = "EFFECTIVE";
    public static final String RELEASED = "RELEASED";

    public boolean isPending() {
        return PENDING.equals(status);
    }

    public boolean isEffective() {
        return EFFECTIVE.equals(status);
    }
}
