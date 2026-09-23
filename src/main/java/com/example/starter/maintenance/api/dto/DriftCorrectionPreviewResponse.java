package com.example.starter.maintenance.api.dto;

import java.util.List;

/**
 * 漂移修正预览响应：区间内全部读数的旧值、新值、插值段和受影响保养项目；不写任何数据。
 *
 * @param equipmentId       所属设备标识
 * @param correctionKey     修正单业务标识（回显，预览不占键）
 * @param anchorCount       锚点数量
 * @param anchors           规范化排序后的锚点列表
 * @param items             区间内受影响读数明细（按采样时刻升序）
 * @param maintenanceItems  受影响保养项目（修正前实际状态与修正后投影状态）
 */
public record DriftCorrectionPreviewResponse(
        String equipmentId,
        String correctionKey,
        int anchorCount,
        List<DriftAnchorView> anchors,
        List<DriftCorrectionItemView> items,
        List<MaintenanceItemView> maintenanceItems) {
}
