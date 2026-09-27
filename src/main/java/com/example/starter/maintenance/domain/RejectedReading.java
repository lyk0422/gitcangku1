package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 隔离生效期间被拒绝的新增读数证据（只追加，不可改写）。
 *
 * @param id                 自增主键（稳定排序依据）
 * @param equipmentId        设备标识
 * @param orderId            当时生效的隔离令标识（锁定点集合经该令固化）
 * @param readingId          被拒读数标识
 * @param sampledAt          被拒读数 UTC 采样时刻
 * @param cumulativeMinutes  被拒读数申报的累计工时（分钟）
 * @param requestedBy        提交操作人；请求未提供操作者时为 null
 * @param requestId          被拒请求的 requestId
 * @param rejectedAt         拒绝发生时刻（UTC）
 */
public record RejectedReading(
        long id,
        String equipmentId,
        String orderId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        String requestedBy,
        String requestId,
        Instant rejectedAt) {
}
