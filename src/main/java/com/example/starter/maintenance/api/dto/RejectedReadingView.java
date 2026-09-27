package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 隔离生效期间被拒新增读数的诊断视图（证据固化，不可改写）。
 *
 * @param rejectedId         拒绝记录标识
 * @param equipmentId        设备标识
 * @param isolationId        当时生效的隔离令标识
 * @param readingId          被拒读数标识
 * @param sampledAt          被拒读数的 UTC 采样时刻
 * @param cumulativeMinutes  被拒读数累计工时（分钟）
 * @param operatorId         提交操作人；请求未提供时为 null
 * @param requestId          提交请求 requestId；请求未提供时为 null
 * @param lockPointsSnapshot 当时隔离令锁定点标识集合的规范化快照
 * @param rejectedAt         拒绝时刻（UTC）
 */
public record RejectedReadingView(
        long rejectedId,
        String equipmentId,
        long isolationId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        String operatorId,
        String requestId,
        String lockPointsSnapshot,
        Instant rejectedAt) {
}
