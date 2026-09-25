package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 质量标记复核成功响应：固化复核记录，并返回复核后的当前版本置信度。
 *
 * @param flagKey            质量标记唯一标识
 * @param observationId      所属观测记录唯一标识
 * @param flagVersion        标记绑定版本
 * @param reviewVersion      复核时观测当前版本（与 flagVersion 一致）
 * @param conclusion         复核结论
 * @param reason             复核理由
 * @param reviewedBy         复核角色
 * @param confidenceAfter    复核后的当前版本置信度
 * @param confidenceChanged  本次复核是否实际扣减了置信度（同版本同类别重复 CONFIRMED 为 false）
 * @param createdAt          复核记录写入时间（服务器时区）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QualityFlagReviewResponse(
        String flagKey,
        String observationId,
        int flagVersion,
        int reviewVersion,
        ReviewConclusion conclusion,
        String reason,
        String reviewedBy,
        int confidenceAfter,
        boolean confidenceChanged,
        LocalDateTime createdAt) {
}
