package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 停机区间视图。
 *
 * @param downtimeKey       停机业务键，全局唯一
 * @param equipmentId       所属设备标识
 * @param startAt           停机开始时刻（UTC，含）
 * @param endAt             停机结束时刻（UTC，不含）
 * @param reason            停机原因
 * @param deductionMinutes  区间扣减量（分钟）；生效区间随读数变更新算，撤销后固化
 * @param status            ACTIVE=生效，REVOKED=已撤销
 * @param createdAt         停机登记时刻（UTC）
 * @param revokedAt         撤销时刻（UTC），未撤销为 null
 * @param equipmentVersion  操作后的设备版本号（仅写操作响应中有意义，查询时为当前版本）
 */
public record DowntimeResponse(
        String downtimeKey,
        String equipmentId,
        Instant startAt,
        Instant endAt,
        String reason,
        long deductionMinutes,
        String status,
        Instant createdAt,
        Instant revokedAt,
        long equipmentVersion) {
}
