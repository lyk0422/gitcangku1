package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 设备停机区间记录（登记后不可改写；撤销仅追加状态，记录保留）。
 *
 * @param downtimeKey      停机区间全局唯一标识
 * @param equipmentId      所属设备标识
 * @param startAt          UTC 开始时刻（含，左闭）
 * @param endAt            UTC 结束时刻（不含，右开），严格晚于开始时刻
 * @param reason           停机原因，非空
 * @param status           状态：ACTIVE 生效 / CANCELLED 已撤销
 * @param requestId        登记停机的请求 requestId
 * @param createdAt        登记时刻（UTC）
 * @param revokedAt        撤销时刻（UTC），未撤销时为 null
 * @param revokeRequestId  撤销请求 requestId，未撤销时为 null
 */
public record DowntimeRecord(
        String downtimeKey,
        String equipmentId,
        Instant startAt,
        Instant endAt,
        String reason,
        String status,
        String requestId,
        Instant createdAt,
        Instant revokedAt,
        String revokeRequestId) {

    public static final String ACTIVE = "ACTIVE";
    public static final String CANCELLED = "CANCELLED";
}
