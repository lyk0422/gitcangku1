package com.example.starter.permit;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 采样提交/批量合并拒绝原因记录：对应 permit_rejection_log 表一行，仅追加、独立于业务事务。
 *
 * @param id             自增主键
 * @param requestId      被拒绝请求标识（不唯一）
 * @param operation      SUBMIT / BATCH_MERGE
 * @param observationKey 被拒绝观测业务键；批次级失败为 null
 * @param deviceId       采集设备标识；批次级失败为 null
 * @param gridLon        采集网格点经度；批次级失败为 null
 * @param gridLat        采集网格点纬度；批次级失败为 null
 * @param sampledAt      采集时刻（UTC）；批次级失败为 null
 * @param reasonCode     机器可区分原因码
 * @param reasonMessage  可读原因描述
 * @param rejectedAt     拒绝发生时刻（UTC）
 */
public record RejectionEntry(
        long id,
        String requestId,
        String operation,
        String observationKey,
        String deviceId,
        BigDecimal gridLon,
        BigDecimal gridLat,
        Instant sampledAt,
        String reasonCode,
        String reasonMessage,
        Instant rejectedAt) {
}
