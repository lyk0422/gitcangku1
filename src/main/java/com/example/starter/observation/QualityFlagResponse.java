package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 质量标记响应：标记历史与待复核清单均返回该结构。
 *
 * @param flagKey       质量标记唯一标识
 * @param observationId 所属观测记录唯一标识
 * @param version       标记绑定的观测版本号
 * @param category      质量问题类别
 * @param description   质量问题说明
 * @param submittedBy   标记提交角色
 * @param status        标记状态：PENDING / CONFIRMED / DISMISSED / STALE
 * @param reviewedBy    复核角色；未复核时不返回
 * @param reviewReason  复核理由；未复核时不返回
 * @param reviewVersion 复核成功时固化的观测版本号；未复核或 STALE 时不返回
 * @param createdAt     标记创建时间（服务器时区）
 * @param reviewedAt    复核处理时间（服务器时区）；未复核时不返回
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QualityFlagResponse(
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

    public static QualityFlagResponse of(QualityFlag flag) {
        return new QualityFlagResponse(flag.flagKey(), flag.observationId(), flag.version(),
                flag.category(), flag.description(), flag.submittedBy(), flag.status(),
                flag.reviewedBy(), flag.reviewReason(), flag.reviewVersion(),
                flag.createdAt(), flag.reviewedAt());
    }
}
