package com.example.starter.snow.model;

import java.time.Instant;

/**
 * 解除审核行：一次针对控制区的双人解除审核及其三小时读数快照。
 *
 * @param id 主键
 * @param regionId 控制区标识
 * @param status PENDING/COMPLETED/INVALIDATED
 * @param windowStart 窗口起始整点（UTC，含）
 * @param windowEnd 窗口结束整点（UTC，含）
 * @param readingIds 窗口内三条读数主键快照（逗号分隔）
 * @param firstReviewer 第一名确认审核员，未确认为 null
 * @param requestId 发起审核幂等键
 * @param invalidatedReason 失效原因，未失效为 null
 * @param createdAt 发起时间（UTC）
 * @param completedAt 完成时间（UTC），未完成为 null
 */
public record ReviewRec(long id, String regionId, String status, Instant windowStart, Instant windowEnd,
                        String readingIds, String firstReviewer, String requestId,
                        String invalidatedReason, Instant createdAt, Instant completedAt) {

    public static final String PENDING = "PENDING";
    public static final String COMPLETED = "COMPLETED";
    public static final String INVALIDATED = "INVALIDATED";
}