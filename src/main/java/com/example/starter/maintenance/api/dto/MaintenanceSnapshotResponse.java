package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 工单关闭时写入的不可变保养状态快照视图。
 *
 * @param snapshotId                 快照标识
 * @param workOrderId                所属工单标识
 * @param equipmentId                所属设备标识
 * @param baselineReadingId          基线读数标识
 * @param baselineRevisionNo         基线读数修订号
 * @param baselineSampledAt          基线读数采样时刻（UTC）
 * @param baselineCumulativeMinutes  基线读数累计工时（分钟）
 * @param lastValidReadingId         最后有效读数标识
 * @param lastValidRevisionNo        最后有效读数修订号
 * @param lastValidSampledAt         最后有效读数采样时刻（UTC）
 * @param lastValidCumulativeMinutes 最后有效读数累计工时（分钟）
 * @param closedAt                   工单关闭时刻（UTC）
 */
public record MaintenanceSnapshotResponse(
        long snapshotId,
        String workOrderId,
        String equipmentId,
        String baselineReadingId,
        int baselineRevisionNo,
        Instant baselineSampledAt,
        long baselineCumulativeMinutes,
        String lastValidReadingId,
        int lastValidRevisionNo,
        Instant lastValidSampledAt,
        long lastValidCumulativeMinutes,
        Instant closedAt) {
}
