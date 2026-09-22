package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 成绩榜中的单个条目。
 *
 * @param bib          参赛号
 * @param rank         名次（并列同名次并跳号）；未计时/取消资格/漏点为 null
 * @param status       RANKED / UNTIMED / DISQUALIFIED / MISSING_CHECKPOINT
 * @param finishTimeMs 原始完赛耗时毫秒；计时缺失为 null
 * @param penaltyMs    生效加时合计毫秒数
 * @param totalTimeMs  总耗时毫秒；未排名为 null
 * @param splits       按检查点顺序排列的分段明细（缺失检查点 elapsedMillis 为 null）；
 *                     赛事未配置检查点时为 null（响应中省略）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultEntryResponse(
        String bib,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        List<SplitDetailResponse> splits
) {
}
