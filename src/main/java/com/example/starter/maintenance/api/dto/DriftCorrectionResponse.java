package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 漂移修正激活响应。每条受影响读数生成一个新修订号（旧版本不可变），
 * 全部保养项目按修正后当前累计工时一次性重算，恰好生成一个 maintenanceSnapshotVersion。
 *
 * @param correctionKey               修正单业务标识
 * @param equipmentId                 所属设备标识
 * @param anchorCount                 锚点数量
 * @param affectedCount               区间内受影响读数条数
 * @param maintenanceSnapshotVersion  本次激活生成的保养快照版本号（每设备从 1 递增）
 * @param equipmentVersion            激活后的设备版本号
 * @param anchors                     规范化排序后的锚点列表
 * @param items                       受影响读数明细（按采样时刻升序）
 * @param maintenanceItems            受影响保养项目（含重算后状态与下一阈值）
 * @param activatedAt                 激活时刻（UTC）
 */
public record DriftCorrectionResponse(
        String correctionKey,
        String equipmentId,
        int anchorCount,
        int affectedCount,
        long maintenanceSnapshotVersion,
        long equipmentVersion,
        List<DriftAnchorView> anchors,
        List<DriftCorrectionItemView> items,
        List<MaintenanceItemView> maintenanceItems,
        Instant activatedAt) {
}
