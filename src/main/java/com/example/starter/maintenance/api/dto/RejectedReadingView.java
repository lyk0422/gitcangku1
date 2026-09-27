package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 隔离期间被拒新增读数视图（诊断证据，只追加，按拒绝时刻与自增 id 升序）。
 *
 * @param id                 证据自增标识
 * @param equipmentId        设备标识
 * @param orderId            当时生效隔离令标识
 * @param readingId          被拒读数标识
 * @param sampledAt          被拒读数 UTC 采样时刻
 * @param cumulativeMinutes  被拒读数申报累计工时（分钟）
 * @param requestedBy        提交操作人；请求未提供时为 null
 * @param requestId          被拒请求 requestId
 * @param rejectedAt         拒绝发生时刻（UTC）
 */
public record RejectedReadingView(
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
