package com.example.starter.race.persistence;

import com.example.starter.race.domain.ReclassRequestStatus;

/**
 * reclass_request 表行记录。
 *
 * @param requestId    重分申请ID，全局唯一（兼作提交幂等键）
 * @param raceId       所属赛事ID
 * @param bib          申请参赛者参赛号
 * @param fromCategory 申请提交时参赛者所属组别；null 表示未分组
 * @param toCategory   目标组别代码
 * @param reason       申请理由
 * @param status       申请状态：PENDING / APPROVED / REJECTED
 * @param raceVersion  提交时客户端所见赛事版本（expectedVersion）
 * @param batchId      批准批次ID（=批准请求的 reclassKey）；未批准为 null
 * @param rejectReason 拒绝理由；未拒绝为 null
 * @param createdAt    申请提交时间，Unix毫秒时间戳
 * @param decidedAt    审批时间，Unix毫秒时间戳；待审批为 null
 */
public record ReclassRequestRow(
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
