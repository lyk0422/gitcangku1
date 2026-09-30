package com.example.starter.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 申请详情响应；深度、潮高、余量单位均为米，时间为上海本地时间。
 *
 * @param status           申请状态：CONFIRMED/PENDING_REVIEW/REJECTED
 * @param rejectionCode    机器可区分拒绝原因，未拒绝为 null
 * @param rejectionReason  拒绝原因说明，未拒绝为 null
 * @param tideVersionNo    校核所用潮高版本号，无有效潮高数据时为 null
 * @param staticDepth      泊位静态水深（米）
 * @param minTideHeight    申请区间内最低有效潮高（米）
 * @param availableDepth   可用深度=静态水深+最低潮高（米）
 * @param clearance        余量=可用深度-(吃水+安全余量)（米）
 * @param snapTideVersionNo 确认决策快照潮高版本号，从未确认为 null
 * @param snapMinTideHeight 确认决策快照最低潮高（米）
 * @param snapAvailableDepth 确认决策快照可用深度（米）
 */
public record ApplicationResponse(
        String requestId,
        String vesselName,
        BigDecimal draft,
        BigDecimal safetyMargin,
        String berthCode,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String status,
        String rejectionCode,
        String rejectionReason,
        BigDecimal staticDepth,
        Long tideVersionNo,
        BigDecimal minTideHeight,
        BigDecimal availableDepth,
        BigDecimal clearance,
        Long snapTideVersionNo,
        BigDecimal snapMinTideHeight,
        BigDecimal snapAvailableDepth,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime confirmedAt,
        LocalDateTime reviewedAt) {
}
