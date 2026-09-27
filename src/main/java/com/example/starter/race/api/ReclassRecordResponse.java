package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 不可变重分记录响应：保留迁移前名次与计时快照。
 *
 * @param recordId        记录ID（自增）
 * @param batchId         批准批次ID（=批准请求的 reclassKey）
 * @param raceId          所属赛事ID
 * @param bib             被迁移参赛者参赛号
 * @param fromCategory    迁移前所属组别；未分组为 null
 * @param toCategory      迁移后所属组别
 * @param preRank         迁移前在来源组别中的名次；未排名为 null
 * @param preStatus       迁移前成绩状态
 * @param preFinishTimeMs 迁移前原始完赛耗时（毫秒）快照；计时缺失为 null
 * @param prePenaltyMs    迁移前生效加时合计（毫秒）快照
 * @param preTotalTimeMs  迁移前总耗时（毫秒）快照；未排名为 null
 * @param operator        批准操作者
 * @param approvedAt      批准时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReclassRecordResponse(
        long recordId,
        String batchId,
        String raceId,
        String bib,
        String fromCategory,
        String toCategory,
        Integer preRank,
        EntryStatus preStatus,
        Long preFinishTimeMs,
        long prePenaltyMs,
        Long preTotalTimeMs,
        String operator,
        long approvedAt
) {
}
