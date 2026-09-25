package com.example.starter.observation;

import java.time.LocalDateTime;

/**
 * 质量标记实体：对应 quality_flag 表一行，标记附加于提交时观测的当前版本。
 *
 * @param flagKey       质量标记唯一标识
 * @param observationId 所属观测记录唯一标识
 * @param version       标记提交时绑定的观测版本号
 * @param category      质量问题类别
 * @param description   质量问题说明
 * @param submittedBy   标记提交角色
 * @param status        标记状态：PENDING / CONFIRMED / DISMISSED / STALE
 * @param reviewedBy    复核角色；未复核为 null
 * @param reviewReason  复核理由；未复核为 null
 * @param reviewVersion 复核成功时固化的观测当前版本号；未复核或 STALE 为 null
 * @param createdAt     标记创建时间（服务器时区）
 * @param reviewedAt    复核处理时间（服务器时区）；未复核为 null
 */
public record QualityFlag(
        String flagKey,
        String observationId,
        int version,
        QualityCategory category,
        String description,
        String submittedBy,
        FlagStatus status,
        String reviewedBy,
        String reviewReason,
        Integer reviewVersion,
        LocalDateTime createdAt,
        LocalDateTime reviewedAt) {
}
