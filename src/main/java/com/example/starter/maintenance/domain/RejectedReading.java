package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 被隔离拒绝的新增读数证据（独立事务固化，读数本身不落库）。
 *
 * @param rejectedId         拒绝记录标识，亦为稳定排序键
 * @param equipmentId        所属设备标识
 * @param orderId            当时生效（或待生效）的隔离令标识
 * @param requestId          被拒请求 requestId（失败不占幂等键）
 * @param operatorId         提交操作人；请求未提供时为 null
 * @param readingId          被拒读数标识
 * @param sampledAt          被拒读数 UTC 采样时刻
 * @param cumulativeMinutes  被拒读数申报累计工时（分钟）
 * @param expectedVersion    被拒请求携带的设备期望版本号
 * @param lockPointIds       被拒时刻锁定点标识集合快照（按标识升序的规范化 JSON 数组）
 * @param reasonCode         拒绝原因码
 * @param rejectedAt         拒绝时刻（UTC）
 */
public record RejectedReading(
        long rejectedId,
        String equipmentId,
        long orderId,
        String requestId,
        String operatorId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        long expectedVersion,
        String lockPointIds,
        String reasonCode,
        Instant rejectedAt) {
}
