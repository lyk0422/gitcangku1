package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 漂移修正预览结果：只读，不写数据。包含区间内全部读数的旧值/新值/插值段、
 * 受影响保养项目的拟重算结果以及边界校验结论。
 *
 * @param correctionKey   修正单业务唯一键
 * @param equipmentId     所属设备标识
 * @param intervalStart   首锚点采样时刻（UTC）
 * @param intervalEnd     尾锚点采样时刻（UTC）
 * @param anchors         规范化后的锚点（按采样时刻升序）
 * @param readings        闭区间内全部读数（按采样时刻升序），含旧值、新值与插值段
 * @param maintenanceItems 受影响保养项目的拟重算结果
 * @param activatable     是否满足全部激活前置条件（冻结点、单调、区间外相邻冲突等）
 * @param rejectionCode   不可激活时的稳定错误码；可激活时为 null
 */
public record DriftPreviewResponse(
        String correctionKey,
        String equipmentId,
        Instant intervalStart,
        Instant intervalEnd,
        List<DriftAnchorView> anchors,
        List<DriftReadingView> readings,
        List<DriftMaintenanceItemView> maintenanceItems,
        boolean activatable,
        String rejectionCode) {
}
