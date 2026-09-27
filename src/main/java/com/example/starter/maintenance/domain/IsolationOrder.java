package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 能源隔离令。
 *
 * @param orderId           隔离令唯一标识
 * @param equipmentId       被隔离设备标识
 * @param status            状态：PENDING / ACTIVE / RELEASED
 * @param createdBy         创建操作者标识
 * @param requestId         创建请求 requestId
 * @param createdAt         创建时刻（UTC）
 * @param activatedAt       隔离生效时刻（UTC）；未生效为 null
 * @param activatedBy       触发隔离生效（最后一个集齐确认）的操作者；未生效为 null
 * @param activatedRequestId 触发隔离生效的确认请求 requestId；未生效为 null
 * @param releasedAt        解除完成时刻（UTC，区间右端点开区间）；未解除为 null
 * @param releasedBy        解除操作者标识；未解除为 null
 * @param releaseReason     解除原因；未解除为 null
 * @param releaseRequestId  解除请求 requestId；未解除为 null
 */
public record IsolationOrder(
        String orderId,
        String equipmentId,
        String status,
        String createdBy,
        String requestId,
        Instant createdAt,
        Instant activatedAt,
        String activatedBy,
        String activatedRequestId,
        Instant releasedAt,
        String releasedBy,
        String releaseReason,
        String releaseRequestId) {

    public static final String PENDING = "PENDING";
    public static final String ACTIVE = "ACTIVE";
    public static final String RELEASED = "RELEASED";
}
