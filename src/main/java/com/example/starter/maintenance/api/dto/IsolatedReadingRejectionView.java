package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 隔离生效期被拒新增读数的证据视图（固化锁定点集合快照与操作人，不可改写）。
 *
 * @param rejectionId      拒绝记录标识
 * @param equipmentId      读数目标设备标识
 * @param orderId          当时生效的隔离令标识
 * @param readingId        被拒读数标识
 * @param operatorId       提交读数的操作人；请求未提供时为 null
 * @param requestId        被拒读数请求 requestId
 * @param sampledAt        被拒读数声称的 UTC 采样时刻
 * @param cumulativeMinutes 被拒读数声称的累计工时（分钟）
 * @param lockPointCount   拒绝时刻锁定点数量（实际值快照）
 * @param lockPointSnapshot 拒绝时刻锁定点标识集合快照（按标识升序）
 * @param rejectedAt       拒绝发生时刻（UTC）
 */
public record IsolatedReadingRejectionView(
        long rejectionId,
        String equipmentId,
        String orderId,
        String readingId,
        String operatorId,
        String requestId,
        Instant sampledAt,
        long cumulativeMinutes,
        int lockPointCount,
        List<String> lockPointSnapshot,
        Instant rejectedAt) {
}
