package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 保养工单：创建时冻结基线工时读数（当前已认证读数，即设备最新生效读数）与
 * 允许登记的 UTC 左闭右开窗口 [windowStart, windowEnd)。
 *
 * @param workOrderKey                  工单标识（客户端提供，全局唯一，兼作各写操作的幂等键）
 * @param equipmentId                   所属设备标识
 * @param status                        工单状态（见 {@link WorkOrderStatus}）
 * @param workOrderVersion              工单版本号，初始 1；开始/登记读数/关闭/取消/终止均校验并加一
 * @param baselineReadingId             基线读数标识（创建时冻结）
 * @param baselineSampledAt             基线读数 UTC 采样时刻（创建时冻结）
 * @param baselineCumulativeMinutes     基线累计工时（分钟，创建时冻结）
 * @param windowStart                   允许登记窗口起始时刻（UTC，含）
 * @param windowEnd                     允许登记窗口结束时刻（UTC，不含），必须晚于 windowStart
 * @param startedAt                     开始时刻（UTC），未开始为 null
 * @param closedAt                      关闭时刻（UTC），未关闭为 null
 * @param cancelledAt                   取消时刻（UTC），未取消为 null
 * @param terminatedAt                  终止时刻（UTC），未终止为 null
 * @param snapshotLastReadingId         关闭快照：最后有效读数标识（无窗口内有效读数时取基线读数）
 * @param snapshotLastSampledAt         关闭快照：最后有效读数 UTC 采样时刻
 * @param snapshotLastCumulativeMinutes 关闭快照：最后有效读已累计工时（分钟）
 * @param createdAt                     建单时刻（UTC）
 * @param updatedAt                     最近一次状态变更时刻（UTC）
 */
public record WorkOrder(
        String workOrderKey,
        String equipmentId,
        WorkOrderStatus status,
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
        Instant createdAt,
        Instant updatedAt) {
}
