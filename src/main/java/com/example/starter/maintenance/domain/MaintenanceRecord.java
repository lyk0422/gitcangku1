package com.example.starter.maintenance.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 保养记录（锚点快照，不允许删除）。
 *
 * @param maintenanceId           保养记录主键
 * @param equipmentId             所属设备标识
 * @param readingId               锚点读数标识
 * @param anchorRevisionNo        锚点读数在保养完成时的修订号
 * @param anchorSampledAt         锚点读数的 UTC 采样时刻（快照）
 * @param anchorVirtualHours      锚点读数的跨表虚拟工时快照（小时），随链条重算刷新
 * @param completedAt             保养完成登记时刻（UTC）
 */
public record MaintenanceRecord(
        long maintenanceId,
        String equipmentId,
        String readingId,
        int anchorRevisionNo,
        Instant anchorSampledAt,
        BigDecimal anchorVirtualHours,
        Instant completedAt) {

    /** 锚点虚拟工时换算分钟（四舍五入）。 */
    public long anchorCumulativeMinutes() {
        return anchorVirtualHours.multiply(BigDecimal.valueOf(60))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
    }
}
