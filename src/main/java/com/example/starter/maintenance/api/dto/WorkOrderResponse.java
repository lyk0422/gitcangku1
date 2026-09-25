package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 保养工单视图（含冻结基线、登记窗口与工单状态）。
 *
 * @param workOrderId                工单标识
 * @param equipmentId                所属设备标识
 * @param version                    工单当前版本号
 * @param status                     CREATED / IN_PROGRESS / CLOSED / CANCELLED / TERMINATED
 * @param baselineReadingId          基线读数标识
 * @param baselineRevisionNo         基线读数建单时修订号
 * @param baselineSampledAt          基线读数采样时刻（UTC）
 * @param baselineCumulativeMinutes  基线读数累计工时（分钟）
 * @param windowStart                允许登记窗口起点（UTC，含）
 * @param windowEnd                  允许登记窗口终点（UTC，不含）
 * @param lastValidReadingId         最近有效读数标识（无新读数时为基线读数）
 * @param lastValidSampledAt         最近有效读数采样时刻（UTC）
 * @param lastValidCumulativeMinutes 最近有效读数累计工时（分钟）
 * @param lastValidRevisionNo        最近有效读数修订号
 * @param createdAt                  建单时刻（UTC）
 * @param startedAt                  开始时刻（UTC）
 * @param closedAt                   关闭时刻（UTC）
 * @param cancelledAt                取消时刻（UTC）
 * @param terminatedAt               终止时刻（UTC）
 * @param terminateReason            终止原因
 * @param equipmentVersion           操作后的设备版本号（查询时为当前设备版本）
 */
public record WorkOrderResponse(
        String workOrderId,
        String equipmentId,
        long version,
        String status,
        String baselineReadingId,
        int baselineRevisionNo,
        Instant baselineSampledAt,
        long baselineCumulativeMinutes,
        Instant windowStart,
        Instant windowEnd,
        String lastValidReadingId,
        Instant lastValidSampledAt,
        Long lastValidCumulativeMinutes,
        Integer lastValidRevisionNo,
        Instant createdAt,
        Instant startedAt,
        Instant closedAt,
        Instant cancelledAt,
        Instant terminatedAt,
        String terminateReason,
        long equipmentVersion) {
}
