package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 巡检复核结论实体，对应 inspection_review 表。每条异常巡检至多一条，只追加。
 *
 * @param id           主键
 * @param inspectionId 关联巡检单ID
 * @param reviewerId   复核操作人
 * @param conclusion   复核结论
 * @param note         复核说明，非空
 * @param createdAt    复核结论时刻（Asia/Shanghai）
 */
public record InspectionReview(
        Long id,
        Long inspectionId,
        String reviewerId,
        InspectionConclusion conclusion,
        String note,
        LocalDateTime createdAt) {
}
