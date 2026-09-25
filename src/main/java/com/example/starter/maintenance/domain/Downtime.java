package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 设备停机区间（左闭右开 [startAt, endAt)）。撤销后记录保留且不可改写。
 *
 * @param downtimeId        停机记录主键
 * @param downtimeKey       停机业务键，全局唯一（含已撤销记录）
 * @param equipmentId       所属设备标识
 * @param startAt           停机开始时刻（UTC，含）
 * @param endAt             停机结束时刻（UTC，不含），须晚于开始时刻
 * @param reason            停机原因，非空
 * @param deductionMinutes  区间扣减量（分钟）：不晚于结束时刻的最近读数累计分钟减去不晚于开始时刻的
 *                          最近读数累计分钟，取不到读数时为 0；读数新增/修订时重算，撤销后固化
 * @param status            状态：ACTIVE=生效（参与扣减），REVOKED=已撤销（不参与扣减）
 * @param createdAt         停机登记时刻（UTC）
 * @param revokedAt         撤销时刻（UTC），未撤销为 null
 */
public record Downtime(
        long downtimeId,
        String downtimeKey,
        String equipmentId,
        Instant startAt,
        Instant endAt,
        String reason,
        long deductionMinutes,
        String status,
        Instant createdAt,
        Instant revokedAt) {

    /** 状态：生效。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 状态：已撤销。 */
    public static final String STATUS_REVOKED = "REVOKED";
}
