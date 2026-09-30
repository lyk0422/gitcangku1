package com.example.starter.race.persistence;

import com.example.starter.race.domain.ReclassStatus;

/**
 * reclass_request 表行记录（组别重分申请）。
 *
 * @param requestId       申请ID，全局唯一，同时作为提交申请的幂等键
 * @param raceId          所属赛事ID
 * @param bib             申请人参赛号
 * @param fromCategory    申请时选手所属组别
 * @param toCategory      目标组别
 * @param reason          申请理由
 * @param status          申请状态：PENDING/APPROVED/REJECTED
 * @param expectedVersion 提交时客户端所见赛事版本
 * @param createdAt       申请提交时间，Unix毫秒时间戳
 * @param resolvedAt      审批时间，Unix毫秒时间戳；未审批为 null
 */
public record ReclassRequestRow(
        String requestId,
        String raceId,
        String bib,
        String fromCategory,
        String toCategory,
        String reason,
        ReclassStatus status,
        int expectedVersion,
        long createdAt,
        Long resolvedAt
) {
}
