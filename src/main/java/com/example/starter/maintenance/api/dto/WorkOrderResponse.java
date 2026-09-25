package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 保养工单视图：基线、登记窗口、读数诊断、关闭快照与取消限制。
 *
 * @param workOrderKey                  工单标识
 * @param equipmentId                   所属设备标识
 * @param status                        工单状态（CREATED/STARTED/CLOSED/CANCELLED/TERMINATED）
 * @param workOrderVersion              工单当前版本号
 * @param baselineReadingId             基线读数标识（创建时冻结的当前已认证读数）
 * @param baselineSampledAt             基线读数 UTC 采样时刻
 * @param baselineCumulativeMinutes     基线累计工时（分钟）
 * @param windowStart                   允许登记窗口起始时刻（UTC，含）
 * @param windowEnd                     允许登记窗口结束时刻（UTC，不含）
 * @param startedAt                     开始时刻（UTC），未开始为 null
 * @param closedAt                      关闭时刻（UTC），未关闭为 null
 * @param cancelledAt                   取消时刻（UTC），未取消为 null
 * @param terminatedAt                  终止时刻（UTC），未终止为 null
 * @param snapshotLastReadingId         关闭快照：最后有效读数标识（仅 CLOSED 有值，不可变）
 * @param snapshotLastSampledAt         关闭快照：最后有效读数 UTC 采样时刻（仅 CLOSED 有值）
 * @param snapshotLastCumulativeMinutes 关闭快照：最后有效读数累计工时（仅 CLOSED 有值）
 * @param readingsInWindow              读数诊断：当前落在窗口内的读数条数
 * @param lastValidReadingId            读数诊断：窗口内不低于基线的最后有效读数标识（无则为 null）
 * @param lastValidSampledAt            读数诊断：最后有效读数 UTC 采样时刻（无则为 null）
 * @param lastValidCumulativeMinutes    读数诊断：最后有效读数累计工时（无则为 null）
 * @param cancellable                   取消限制：当前是否允许取消（仅 CREATED 允许）
 * @param cancelRestriction             取消限制原因码（CANCEL_ALLOWED / ALREADY_STARTED / ALREADY_CLOSED / ALREADY_CANCELLED / ALREADY_TERMINATED）
 * @param equipmentVersion              操作后的设备版本号（查询时为当前版本）
 */
public record WorkOrderResponse(
        String workOrderKey,
        String equipmentId,
        String status,
        long workOrderVersion,
        String baselineReadingId,
        Instant baselineSampledAt,
        long baselineCumulativeMinutes,
        Instant windowStart,
        Instant windowEnd,
        Instant startedAt,
        Instant closedAt,
        Instant cancelledAt,
        Instant terminatedAt,
        String snapshotLastReadingId,
        Instant snapshotLastSampledAt,
        Long snapshotLastCumulativeMinutes,
        int readingsInWindow,
        String lastValidReadingId,
        Instant lastValidSampledAt,
        Long lastValidCumulativeMinutes,
        boolean cancellable,
        String cancelRestriction,
        long equipmentVersion) {
}
