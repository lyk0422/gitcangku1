package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 漂移修正单摘要视图（证据查询，只读，按激活时刻与 correctionKey 稳定排序）。
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
 */
public record DriftCorrectionSummaryView(
        String correctionKey,
        String equipmentId,
        int anchorCount,
        Instant firstSampledAt,
        Instant lastSampledAt,
        int affectedCount,
        long maintenanceSnapshotVersion,
        long equipmentVersion,
        Instant createdAt) {
}
