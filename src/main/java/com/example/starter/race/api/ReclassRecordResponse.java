package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 不可变重分记录响应。
 *
 * @param recordId         重分记录ID
 * @param reclassKey       批准批次幂等键
 * @param applicationId    来源申请ID
 * @param bib              被迁移选手参赛号
 * @param fromCategoryCode 迁移前所属组别代码；未分组为 null
 * @param toCategoryCode   迁移后所属组别代码
 * @param rankBefore       迁移前在来源组别中的名次；未排名或无来源组别为 null
 * @param finishTimeMs     迁移时原始完赛耗时快照（毫秒）；计时缺失为 null
 * @param penaltyMs        迁移时生效加时合计毫秒数
 * @param totalTimeMs      迁移时总耗时（毫秒）；计时缺失为 null
 * @param operator         批准操作者
 * @param raceVersion      批准提交后的赛事版本号
 * @param createdAt        记录写入时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReclassRecordResponse(
        String recordId,
        String reclassKey,
        String applicationId,
        String bib,
        String fromCategoryCode,
        String toCategoryCode,
        Integer rankBefore,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        String operator,
        int raceVersion,
        long createdAt
) {
}