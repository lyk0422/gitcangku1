package com.example.starter.race.persistence;

import com.example.starter.race.domain.ApplicationStatus;

/**
 * reclass_application 表行记录。
 *
 * @param applicationId  重分申请ID，全局唯一
 * @param raceId         所属赛事ID
 * @param bib            申请人参赛号
 * @param targetCategory 目标组别代码
 * @param reason         申请理由
 * @param status         申请状态
 * @param expectedVersion 提交时客户端所见赛事版本
 * @param createdAt      申请提交时间，Unix毫秒时间戳
 * @param decidedAt      审批时间，Unix毫秒时间戳；待审批为 null
 */
public record ReclassApplicationRow(
        String applicationId,
        String raceId,
        String bib,
        String targetCategory,
        String reason,
        ApplicationStatus status,
        int expectedVersion,
        long createdAt,
        Long decidedAt
) {
}