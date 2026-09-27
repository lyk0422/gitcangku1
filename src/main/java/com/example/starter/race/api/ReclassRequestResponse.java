package com.example.starter.race.api;

import com.example.starter.race.domain.ReclassRequestStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 重分申请响应。
 *
 * @param requestId    申请ID（=提交请求的 requestId）
 * @param raceId       所属赛事ID
 * @param bib          申请参赛者参赛号
 * @param fromCategory 申请提交时所属组别；未分组为 null
 * @param toCategory   目标组别代码
 * @param reason       申请理由
 * @param status       申请状态：PENDING / APPROVED / REJECTED
 * @param raceVersion  提交时客户端所见赛事版本
 * @param batchId      批准批次ID；未批准为 null
 * @param rejectReason 拒绝理由；未拒绝为 null
 * @param createdAt    申请提交时间，Unix毫秒时间戳
 * @param decidedAt    审批时间，Unix毫秒时间戳；待审批为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReclassRequestResponse(
        String requestId,
        String raceId,
        String bib,
        String fromCategory,
        String toCategory,
        String reason,
        ReclassRequestStatus status,
        int raceVersion,
        String batchId,
        String rejectReason,
        long createdAt,
        Long decidedAt
) {
}
