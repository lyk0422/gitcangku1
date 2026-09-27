package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 能源隔离令视图（含全部锁定点与确认/解除数量）。
 *
 * @param orderId                   隔离令标识
 * @param equipmentId               所属设备标识
 * @param status                    令状态：PENDING / EFFECTIVE / RELEASED
 * @param createdBy                 创建操作人
 * @param createdAt                 创建时刻（UTC）
 * @param effectiveAt               生效时刻（UTC）；未生效 null
 * @param releasedAt                解除时刻（UTC）；未解除 null
 * @param releasedBy                解除操作人；未解除 null
 * @param releaseReason             整令解除原因；未解除 null
 * @param equipmentVersion          响应快照时的设备版本号（确认/逐点解除不推进版本）
 * @param lockPointCount            锁定点总数（要求值）
 * @param confirmedLockPointCount   双角色确认完成的点数（实际值）
 * @param releasedLockPointCount    已解除点数（实际值）
 * @param lockPoints                锁定点明细，按提交序号升序
 */
public record IsolationOrderResponse(
        long orderId,
        String equipmentId,
        String status,
        String createdBy,
        Instant createdAt,
        Instant effectiveAt,
        Instant releasedAt,
        String releasedBy,
        String releaseReason,
        long equipmentVersion,
        int lockPointCount,
        int confirmedLockPointCount,
        int releasedLockPointCount,
        List<LockPointView> lockPoints) {
}
