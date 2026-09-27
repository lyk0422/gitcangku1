package com.example.starter.race.api;

import com.example.starter.race.persistence.ReclassRequestRow;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 重分申请响应。
 *
 * @param applicationId  申请ID
 * @param raceId         赛事ID
 * @param bib            申请选手参赛号
 * @param targetCategory 目标组别代码
 * @param reason         申请理由
 * @param status         申请状态：PENDING / APPROVED / REJECTED
 * @param batchId        批准批次ID；未批准为 null
 * @param createdAt      提交时间，Unix毫秒时间戳
 * @param decidedAt      审批时间，Unix毫秒时间戳；待审批为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReclassApplicationResponse(
        String applicationId,
        String raceId,
        String bib,
        String targetCategory,
        String reason,
        ReclassRequestRow.ReclassRequestStatus status,
        String batchId,
        long createdAt,
        Long decidedAt
) {
}
