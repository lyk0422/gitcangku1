package com.example.starter.race.api;

import com.example.starter.race.domain.ReclassRequestStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 组别重分申请响应。
 *
 * @param applicationId      申请ID
 * @param raceId             所属赛事ID
 * @param bib                申请人参赛号
 * @param sourceCategoryCode 提交申请时所属组别代码；未分组为 null
 * @param targetCategoryCode 目标组别代码
 * @param reason             申请理由
 * @param status             申请状态：PENDING / APPROVED / REJECTED
 * @param createdAt          申请提交时间，Unix毫秒时间戳
 * @param decidedAt          审批时间，Unix毫秒时间戳；未审批为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReclassApplicationResponse(
        String applicationId,
        String raceId,
        String bib,
        String sourceCategoryCode,
        String targetCategoryCode,
        String reason,
        ReclassRequestStatus status,
        long createdAt,
        Long decidedAt
) {
}