package com.example.starter.evidence.dto;

import com.example.starter.evidence.InspectionConclusion;

import java.time.LocalDateTime;

/**
 * 巡检复核结论视图，结论带时刻，放行后仍随巡检历史永久保留。
 *
 * @param reviewerId 复核操作人
 * @param conclusion 复核结论
 * @param note       复核说明
 * @param createdAt  复核结论时刻（Asia/Shanghai）
 */
public record InspectionReviewView(
        String reviewerId,
        InspectionConclusion conclusion,
        String note,
        LocalDateTime createdAt) {
}
