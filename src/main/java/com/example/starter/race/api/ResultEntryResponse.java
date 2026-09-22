package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 成绩榜中的单个条目。
 *
 * @param bib          参赛号
 * @param rank         名次（并列同名次并跳号）；未计时/取消资格为 null
 * @param status       RANKED / UNTIMED / DISQUALIFIED
 * @param finishTimeMs 原始完赛耗时毫秒；计时缺失为 null
 * @param penaltyMs    生效加时合计毫秒数
 * @param totalTimeMs  总耗时毫秒；未排名为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultEntryResponse(
        String bib,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs
) {
}
