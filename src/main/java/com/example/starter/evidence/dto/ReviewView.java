package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 双人复核封签记录视图。
 *
 * @param containerKey 容器业务键
 * @param inspectorId  被复核 FAIL 巡检的检查人
 * @param reviewerId   复核保管人
 * @param note         复核说明
 * @param reviewedAt   实际复核时刻（UTC）
 * @param createdAt    记录创建时间（Asia/Shanghai）
 */
public record ReviewView(
        String containerKey,
        String inspectorId,
        String reviewerId,
        String note,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt) {
}
