package com.example.starter.race.persistence;

/**
 * reclass_request 表行记录（组别重分申请）。
 *
 * @param applicationId  申请ID，全局唯一
 * @param raceId         所属赛事ID
 * @param bib            申请选手参赛号
 * @param targetCategory 目标组别代码
 * @param reason         申请理由
 * @param expectedVersion 提交时客户端所见赛事版本
 * @param status         申请状态：PENDING / APPROVED / REJECTED
 * @param batchId        批准批次ID（批准请求的 reclassKey）；未批准为 null
 * @param createdAt      申请提交时间，Unix毫秒时间戳
 * @param decidedAt      审批时间，Unix毫秒时间戳；待审批为 null
 */
public record ReclassRequestRow(
        String applicationId,
        String raceId,
        String bib,
        String targetCategory,
        String reason,
        int expectedVersion,
        ReclassRequestStatus status,
        String batchId,
        long createdAt,
        Long decidedAt
) {

    /** 重分申请状态。 */
    public enum ReclassRequestStatus {
        PENDING,
        APPROVED,
        REJECTED
    }
}
