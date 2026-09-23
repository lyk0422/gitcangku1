package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 漂移修正单详情视图（证据查询，只读）：单头 + 锚点 + 影响明细，均稳定排序。
 *
 * @param correctionKey               修正单业务标识
 * @param equipmentId                 所属设备标识
 * @param anchorCount                 锚点数量
 * @param firstSampledAt              首锚点读数的 UTC 采样时刻（修正区间下界，含）
 * @param lastSampledAt               尾锚点读数的 UTC 采样时刻（修正区间上界，含）
 * @param affectedCount               区间内受影响读数条数
 * @param maintenanceSnapshotVersion  本次激活生成的保养快照版本号
 * @param equipmentVersion            激活后的设备版本号
 * @param createdAt                   激活时刻（UTC）
 * @param anchors                     锚点列表（按采样时刻升序）
 * @param items                       影响明细（按采样时刻升序）
 */
public record DriftCorrectionDetailView(
        String correctionKey,
        String equipmentId,
        int anchorCount,
        Instant firstSampledAt,
        Instant lastSampledAt,
        int affectedCount,
        long maintenanceSnapshotVersion,
        long equipmentVersion,
        Instant createdAt,
        List<DriftAnchorView> anchors,
        List<DriftCorrectionItemView> items) {
}
