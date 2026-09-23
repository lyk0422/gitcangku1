package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 漂移修正证据查询结果：只读、稳定排序（读数按采样时刻、读数标识升序）。
 *
 * @param correctionKey                修正单唯一键
 * @param equipmentId                  所属设备标识
 * @param equipmentVersionAfter        激活后的设备版本
 * @param maintenanceSnapshotVersion   激活产生的保养快照版本
 * @param intervalStart                首锚点采样时刻（UTC）
 * @param intervalEnd                  尾锚点采样时刻（UTC）
 * @param activatedAt                  激活时刻（UTC）
 * @param anchors                      锚点证据（按位置升序）
 * @param readings                     受影响读数新旧值证据（按采样时刻、读数标识升序）
 * @param maintenanceItems             本次修正一次性重算的全部保养项目（按项目键升序）
 */
public record DriftEvidenceResponse(
        String correctionKey,
        String equipmentId,
        long equipmentVersionAfter,
        long maintenanceSnapshotVersion,
        Instant intervalStart,
        Instant intervalEnd,
        Instant activatedAt,
        List<DriftAnchorView> anchors,
        List<DriftReadingView> readings,
        List<DriftMaintenanceItemView> maintenanceItems) {
}
