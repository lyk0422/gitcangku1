package com.example.starter.observation;

import java.time.LocalDateTime;

/**
 * 质量标记复核记录：对应 quality_flag_review 表的一行，复核成功后写入，不可变。
 *
 * @param observationId     观测记录唯一标识
 * @param flagKey           质量标记标识
 * @param flaggedVersion    标记时观测的版本号
 * @param reviewVersion     复核时观测的当前版本号
 * @param versionConsistent 复核时版本与标记版本是否一致
 * @param conclusion        复核结论：CONFIRMED / DISMISSED
 * @param reason            复核理由
 * @param reviewerRole      复核人角色
 * @param createdAt         复核记录写入时间（服务器时区）
 */
public record QualityFlagReview(
        String observationId,
        String flagKey,
        int flaggedVersion,
        int reviewVersion,
        boolean versionConsistent,
        ReviewConclusion conclusion,
        String reason,
        String reviewerRole,
        LocalDateTime createdAt) {
}
