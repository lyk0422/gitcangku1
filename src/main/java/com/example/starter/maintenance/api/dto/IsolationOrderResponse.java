package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 能源隔离令视图（明细查询与创建/确认/解除响应共用，为首次成功响应的完整快照）。
 *
 * @param orderId                  隔离令唯一标识
 * @param equipmentId              所属设备标识
 * @param state                    PENDING / ISOLATED / RELEASED
 * @param createdBy                创建操作者
 * @param createdAt                创建时刻（UTC）
 * @param effectiveAt              生效时刻（UTC），未生效为 null
 * @param releasedAt               解除时刻（UTC），未解除为 null
 * @param releasedBy               解除操作者，未解除为 null
 * @param releaseReason            令级解除原因，未解除为 null
 * @param lockPoints               锁定点明细（按 seqNo 升序，稳定排序）
 * @param requiredPointCount       要求锁定点总数（实际值/要求值统计之一）
 * @param confirmedPointCount      已被两名角色均确认的锁定点数（实际值）
 * @param requiredConfirmationCount 要求确认次数 = 锁定点数 × 2（要求值）
 * @param confirmationCount        已发生确认次数（实际值）
 * @param remainingConfirmationCount 尚缺确认次数 = 要求确认次数 - 已发生次数（差额）
 * @param releasedPointCount       已逐一解除的锁定点数（实际值）
 * @param remainingReleaseCount    尚待解除的锁定点数（差额）
 */
public record IsolationOrderResponse(
        String orderId,
        String equipmentId,
        String state,
        String createdBy,
        Instant createdAt,
        Instant effectiveAt,
        Instant releasedAt,
        String releasedBy,
        String releaseReason,
        List<LockPointView> lockPoints,
        int requiredPointCount,
        int confirmedPointCount,
        int requiredConfirmationCount,
        int confirmationCount,
        int remainingConfirmationCount,
        int releasedPointCount,
        int remainingReleaseCount) {
}
