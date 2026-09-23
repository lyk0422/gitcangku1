package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 保养快照视图（证据查询，只读，按版本号稳定排序）。
 *
 * @param equipmentId             所属设备标识
 * @param snapshotVersion         快照版本号（每设备从 1 递增，每次漂移修正激活恰好一个）
 * @param correctionKey           产生该快照的漂移修正单标识
 * @param latestReadingId         最新读数标识（无读数时为 null）
 * @param latestSampledAt         最新读数 UTC 采样时刻（无读数时为 null）
 * @param latestCumulativeMillis  修正后最新累计工时（毫秒）
 * @param anchorCumulativeMillis  最近保养锚点累计工时快照（毫秒，无保养时为 0）
 * @param runMillis               本轮运行时长（毫秒）
 * @param periodMinutes           保养周期（分钟）
 * @param status                  保养状态（DUE/NOT_DUE）
 * @param nextThresholdMillis     下一阈值（毫秒）= 最近保养锚点工时 + 保养周期
 * @param createdAt               快照生成时刻（UTC）
 */
public record MaintenanceSnapshotView(
        String equipmentId,
        long snapshotVersion,
        String correctionKey,
        String latestReadingId,
        Instant latestSampledAt,
        long latestCumulativeMillis,
        long anchorCumulativeMillis,
        long runMillis,
        long periodMinutes,
        String status,
        long nextThresholdMillis,
        Instant createdAt) {
}
