package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 隔离令明细视图。涉及数量的字段均同时给出实际值、要求值或差额。
 *
 * @param orderId                隔离令标识
 * @param equipmentId            被隔离设备标识
 * @param status                 PENDING / ACTIVE / RELEASED
 * @param createdBy              创建操作者
 * @param createdAt              创建时刻（UTC）
 * @param activatedAt            生效时刻（UTC），未生效为 null
 * @param releasedAt             解除时刻（UTC，区间右端点开区间），未解除为 null
 * @param releasedBy             解除操作者，未解除为 null
 * @param releaseReason          解除原因，未解除为 null
 * @param lockPointCount         锁定点总数（要求值）
 * @param confirmedLockPointCount 已集齐两名不同角色确认的锁定点数（实际值）
 * @param remainingLockPointCount 距隔离生效尚缺确认的锁定点数（差额）
 * @param releasedLockPointCount 已解除锁定点数（实际值）
 * @param unreleasedLockPointCount 尚未解除锁定点数（差额）
 * @param effectiveFrom          隔离生效区间左端点（闭区间），即 activatedAt
 * @param effectiveTo            隔离生效区间右端点（开区间），即 releasedAt；未解除为 null
 * @param lockPoints             锁定点明细（按创建序号升序）
 */
public record IsolationOrderResponse(
        String orderId,
        String equipmentId,
        String status,
        String createdBy,
        Instant createdAt,
        Instant activatedAt,
        Instant releasedAt,
        String releasedBy,
        String releaseReason,
        int lockPointCount,
        int confirmedLockPointCount,
        int remainingLockPointCount,
        int releasedLockPointCount,
        int unreleasedLockPointCount,
        Instant effectiveFrom,
        Instant effectiveTo,
        List<LockPointDetail> lockPoints) {
}
