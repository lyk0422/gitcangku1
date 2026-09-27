package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 能源隔离令。
 *
 * @param orderId        隔离令唯一标识
 * @param equipmentId    所属设备标识
 * @param state          状态：PENDING 待双角色确认 / ISOLATED 隔离生效 / RELEASED 已解除
 * @param createdBy      创建操作者（创建时固化）
 * @param createdAt      创建时刻（UTC）
 * @param effectiveAt    生效时刻（UTC），未生效为 null
 * @param releasedAt     解除时刻（UTC），未解除为 null
 * @param releasedBy     解除操作者，未解除为 null
 * @param releaseReason  解除原因（令级），未解除为 null
 */
public record IsolationOrder(
        String orderId,
        String equipmentId,
        String state,
        String createdBy,
        Instant createdAt,
        Instant effectiveAt,
        Instant releasedAt,
        String releasedBy,
        String releaseReason) {

    public static final String PENDING = "PENDING";
    public static final String ISOLATED = "ISOLATED";
    public static final String RELEASED = "RELEASED";
}
