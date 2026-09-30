package com.example.starter.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 靠泊申请持久化对象；所有深度、吃水、潮高、余量单位为米，时间为上海本地时间，空值表示尚未产生对应数据。
 */
public record ApplicationPo(
        Long id,
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
        Long tideVersionId,
        Long tideVersionNo,
        BigDecimal minTideHeight,
        BigDecimal availableDepth,
        BigDecimal clearance,
        Long snapTideVersionNo,
        BigDecimal snapMinTideHeight,
        BigDecimal snapAvailableDepth,
        String requestHash,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime confirmedAt,
        LocalDateTime reviewedAt) {
}
