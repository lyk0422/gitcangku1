package com.example.starter.race.persistence;

import com.example.starter.race.domain.ReclassRequestStatus;

/**
 * reclass_request 表行记录。
 *
 * @param applicationId      申请ID，全局唯一
 * @param raceId             所属赛事ID
 * @param bib                申请人参赛号
 * @param sourceCategoryCode 提交申请时所属组别代码；未分组为 null
 * @param targetCategoryCode 目标组别代码
 * @param reason             申请理由
 * @param expectedVersion    提交时客户端所见赛事版本
 * @param status             申请状态：PENDING/APPROVED/REJECTED
 * @param createdAt          申请提交时间，Unix毫秒时间戳
 * @param decidedAt          审批时间，Unix毫秒时间戳；未审批为 null
 */
public record ReclassRequestRow(
        String applicationId,
        String raceId,
        String bib,
        String sourceCategoryCode,
        String targetCategoryCode,
        String reason,
        int expectedVersion,
        ReclassRequestStatus status,
        long createdAt,
        Long decidedAt
) {
}