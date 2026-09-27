package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 能源隔离令视图：含状态、实际/要求的确认数、锁定点总数/已解除数/剩余数（差额）及固化明细。
 *
 * @param isolationId               隔离令标识
 * @param equipmentId               所属设备标识
 * @param state                     PENDING / ACTIVE / RELEASED
 * @param createdBy                 创建操作人
 * @param createdAt                 创建时刻（UTC）
 * @param effectiveAt               生效时刻（UTC）；PENDING 时为 null
 * @param releasedAt                解除完成时刻（UTC）；未解除为 null
 * @param releaseReason             解除原因；未解除为 null
 * @param releasedBy                解除登记操作人；未解除为 null
 * @param confirmedRoleCount        实际已确认角色数（0/1/2）
 * @param requiredRoleCount         要求的确认角色数（2）
 * @param lockPointCount            锁定点总数（要求值）
 * @param releasedLockPointCount    已逐一解除的锁定点数（实际值）
 * @param remainingLockPointCount   尚未解除的锁定点数（差额 = 总数 - 已解除数）
 * @param effectiveDurationSeconds  实际生效时长（秒，左闭右开 [effectiveAt, releasedAt)）；
 *                                  RELEASED 为完整时长，ACTIVE 为截至当前时刻的时长，PENDING 为 null
 * @param lockPoints                锁定点明细，按 seqNo 升序
 * @param confirmations             确认明细，按 seqNo 升序
 * @param equipmentVersion          操作后的设备版本号（写操作响应）；查询历史列表时为 0
 */
public record IsolationResponse(
        long isolationId,
        String equipmentId,
        String state,
        String createdBy,
        Instant createdAt,
        Instant effectiveAt,
        Instant releasedAt,
        String releaseReason,
        String releasedBy,
        int confirmedRoleCount,
        int requiredRoleCount,
        int lockPointCount,
        int releasedLockPointCount,
        int remainingLockPointCount,
        Long effectiveDurationSeconds,
        List<LockPointView> lockPoints,
        List<ConfirmationView> confirmations,
        long equipmentVersion) {
}
