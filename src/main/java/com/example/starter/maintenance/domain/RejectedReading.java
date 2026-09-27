package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 隔离生效期间被拒绝的新增运行读数（诊断证据，固化当时隔离令的锁定点快照与操作人）。
 *
 * @param rejectedId         拒绝记录主键
 * @param equipmentId        提交读数的设备标识
 * @param isolationId        当时生效的隔离令标识
 * @param readingId          被拒读数标识
 * @param sampledAt          被拒读数的 UTC 采样时刻
 * @param cumulativeMinutes  被拒读数的累计工时（分钟）
 * @param operatorId         提交操作人；请求未提供时为 null
 * @param requestId          提交请求 requestId；请求未提供时为 null
 * @param lockPointsJson     当时隔离令锁定点集合的规范化 JSON 快照
 * @param rejectedAt         拒绝时刻（UTC）
 */
public record RejectedReading(
        long rejectedId,
        String equipmentId,
        long isolationId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        String operatorId,
        String requestId,
        String lockPointsJson,
        Instant rejectedAt) {
}
