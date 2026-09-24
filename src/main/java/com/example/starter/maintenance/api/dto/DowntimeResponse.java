package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 停机区间视图（含按当前读数实时计算的扣减明细）。
 *
 * @param downtimeKey            停机区间全局唯一标识
 * @param equipmentId            所属设备标识
 * @param startAt                UTC 开始时刻（含）
 * @param endAt                  UTC 结束时刻（不含）
 * @param reason                 停机原因
 * @param status                 ACTIVE 生效 / CANCELLED 已撤销
 * @param deductionMinutes       按当前读数计算的区间扣减量（分钟）；已撤销固定为 0
 * @param includedInCurrentRun   是否计入当前保养周期扣减（生效且开始时刻不早于最近锚点）
 * @param createdAt              登记时刻（UTC）
 * @param revokedAt              撤销时刻（UTC），未撤销为 null
 * @param equipmentVersion       操作后的设备版本号（仅写操作响应中有意义，查询时为当前版本）
 */
public record DowntimeResponse(
        String downtimeKey,
        String equipmentId,
        Instant startAt,
        Instant endAt,
        String reason,
        String status,
        long deductionMinutes,
        boolean includedInCurrentRun,
        Instant createdAt,
        Instant revokedAt,
        long equipmentVersion) {
}
