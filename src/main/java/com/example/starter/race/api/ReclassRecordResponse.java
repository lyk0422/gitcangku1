package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 不可变组别重分记录响应，保留迁移前名次与计时快照。
 *
 * @param recordId        记录ID
 * @param raceId          所属赛事ID
 * @param bib             被迁移选手参赛号
 * @param applicationId   来源申请ID
 * @param operator        执行批准的操作者
 * @param fromCategory    迁移前所属组别；未分配为 null
 * @param toCategory      迁移后所属组别
 * @param preRank         迁移前在来源组别内的名次；未排名为 null
 * @param preStatus       迁移前成绩状态
 * @param preFinishTimeMs 迁移前原始完赛耗时毫秒；计时缺失为 null
 * @param prePenaltyMs    迁移前生效加时合计毫秒
 * @param preTotalTimeMs  迁移前总耗时毫秒；未排名为 null
 * @param createdAt       迁移提交时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReclassRecordResponse(
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