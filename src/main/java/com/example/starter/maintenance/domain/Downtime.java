package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 停机区间（左闭右开 [startAt, endAt)）。撤销后记录保留且不可改写。
 *
 * @param downtimeKey       停机区间标识，全局唯一
 * @param equipmentId       所属设备标识
 * @param startAt           停机开始时刻（UTC，含）
 * @param endAt             停机结束时刻（UTC，不含），须晚于开始时刻
 * @param reason            停机原因，非空
 * @param deductionMinutes  区间扣减量（分钟），读数新增/修订时重算，撤销后冻结
 * @param status            区间状态：ACTIVE=生效，REVOKED=已撤销
 * @param createdAt         登记时刻（UTC）
 * @param revokedAt         撤销时刻（UTC），未撤销为 null
 */
public record Downtime(
        String downtimeKey,
        String equipmentId,
        Instant startAt,
        Instant endAt,
        String reason,
        long deductionMinutes,
        String status,
        Instant createdAt,
        Instant revokedAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";
}
