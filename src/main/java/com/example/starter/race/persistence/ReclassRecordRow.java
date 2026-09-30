package com.example.starter.race.persistence;

/**
 * reclass_record 表行记录（不可变组别重分记录）。
 *
 * @param recordId          重分记录ID，全局唯一
 * @param raceId            所属赛事ID
 * @param bib               被重分的选手参赛号
 * @param fromCategory      迁移前所属组别
 * @param toCategory        迁移后所属组别
 * @param reason            申请理由（随申请固化）
 * @param applicationId     来源申请ID
 * @param operator          批准操作者
 * @param raceVersionBefore 批准前赛事版本号
 * @param raceVersionAfter  批准后赛事版本号
 * @param previousRank      迁移前在来源组别的名次；未参与排名为 null
 * @param timingSnapshot    迁移前计时快照JSON
 * @param createdAt         批准时间，Unix毫秒时间戳
 */
public record ReclassRecordRow(
        String recordId,
        String raceId,
        String bib,
        String fromCategory,
        String toCategory,
        String reason,
        String applicationId,
        String operator,
        int raceVersionBefore,
        int raceVersionAfter,
        Integer previousRank,
        String timingSnapshot,
        long createdAt
) {
}
