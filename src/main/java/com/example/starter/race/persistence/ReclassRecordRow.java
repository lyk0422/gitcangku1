package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;

/**
 * reclass_record 表行记录（不可变的组别重分记录，保留迁移前名次与计时快照）。
 *
 * @param recordId        重分记录ID，全局唯一
 * @param raceId          所属赛事ID
 * @param bib             被迁移选手参赛号
 * @param applicationId   来源重分申请ID
 * @param operator        执行批准的操作者
 * @param fromCategory    迁移前所属组别代码；未分配组别为 null
 * @param toCategory      迁移后所属组别代码
 * @param preRank         迁移前在来源组别内的名次；未排名为 null
 * @param preStatus       迁移前成绩状态
 * @param preFinishTimeMs 迁移前原始完赛耗时（毫秒）；计时缺失为 null
 * @param prePenaltyMs    迁移前生效加时合计毫秒数
 * @param preTotalTimeMs  迁移前总耗时（毫秒）；未排名为 null
 * @param createdAt       迁移提交时间，Unix毫秒时间戳
 */
public record ReclassRecordRow(
        String recordId,
        String raceId,
        String bib,
        String applicationId,
        String operator,
        String fromCategory,
        String toCategory,
        Integer preRank,
        EntryStatus preStatus,
        Long preFinishTimeMs,
        long prePenaltyMs,
        Long preTotalTimeMs,
        long createdAt
) {
}