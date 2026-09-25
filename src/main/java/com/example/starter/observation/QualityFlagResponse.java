package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 质量标记响应：标记本体字段之外，已复核的标记附带不可变复核记录内容。
 *
 * @param flagKey           质量标记标识
 * @param category          质量问题类别
 * @param description       质量问题说明
 * @param submittedRole     标记提交人角色
 * @param boundVersion      标记时观测的当前版本号
 * @param status            标记状态
 * @param createdAt         标记创建时间（服务器时区）
 * @param conclusion        复核结论（仅已复核时返回）
 * @param reason            复核理由（仅已复核时返回）
 * @param reviewerRole      复核人角色（仅已复核时返回）
 * @param flaggedVersion    复核记录固化的标记时版本（仅已复核时返回）
 * @param reviewVersion     复核记录固化的复核时版本（仅已复核时返回）
 * @param versionConsistent 复核时版本与标记版本是否一致（仅已复核时返回）
 * @param reviewedAt        复核记录写入时间（仅已复核时返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QualityFlagResponse(
        String flagKey,
        QualityCategory category,
        String description,
        String submittedRole,
        int boundVersion,
        FlagStatus status,
        LocalDateTime createdAt,
        ReviewConclusion conclusion,
        String reason,
        String reviewerRole,
        Integer flaggedVersion,
        Integer reviewVersion,
        Boolean versionConsistent,
        LocalDateTime reviewedAt) {

    /**
     * 由标记构造响应（无复核记录）。
     */
    public static QualityFlagResponse of(QualityFlag flag) {
        return new QualityFlagResponse(flag.flagKey(), flag.category(), flag.description(),
                flag.submittedRole(), flag.boundVersion(), flag.status(), flag.createdAt(),
                null, null, null, null, null, null, null);
    }

    /**
     * 由标记与复核记录构造响应。
     */
    public static QualityFlagResponse of(QualityFlag flag, QualityFlagReview review) {
        return new QualityFlagResponse(flag.flagKey(), flag.category(), flag.description(),
                flag.submittedRole(), flag.boundVersion(), flag.status(), flag.createdAt(),
                review.conclusion(), review.reason(), review.reviewerRole(),
                review.flaggedVersion(), review.reviewVersion(), review.versionConsistent(),
                review.createdAt());
    }
}
