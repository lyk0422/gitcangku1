package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 被拒读数证据视图：隔离令解除前新增读数返回 409，读数本身不落库，仅固化证据。
 *
 * @param rejectedId         拒绝记录标识（稳定排序键）
 * @param equipmentId        所属设备标识
 * @param orderId            被拒时的活动隔离令标识
 * @param requestId          被拒请求 requestId（失败不占幂等键）
 * @param operatorId         提交操作人；请求未提供时 null
 * @param readingId          被拒读数标识（未落入读数表）
 * @param sampledAt          被拒读数 UTC 采样时刻
 * @param cumulativeMinutes  被拒读数申报累计工时（分钟）
 * @param expectedVersion    被拒请求携带的设备期望版本号
 * @param lockPointIds       被拒时刻锁定点标识集合快照（按标识升序的规范化 JSON 数组）
 * @param reasonCode         拒绝原因码
 * @param rejectedAt         拒绝时刻（UTC）
 */
public record RejectedReadingView(
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
