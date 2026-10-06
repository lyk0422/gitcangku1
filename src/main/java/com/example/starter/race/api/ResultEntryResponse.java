package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 成绩榜中的单个条目。
 *
 * @param bib                    参赛号
 * @param rank                   名次（并列同名次并跳号）；非 RANKED 为 null
 * @param status                RANKED / UNTIMED / MISSING_CHECKPOINT / DISQUALIFIED / MISSING_FACTOR
 * @param finishTimeMs          原始完赛耗时毫秒；计时缺失为 null
 * @param penaltyMs             生效加时合计毫秒数
 * @param totalTimeMs           总耗时毫秒；未排名为 null
 * @param checkpointCount       赛事检查点总数；未配置检查点为 0
 * @param coveredCheckpointCount 该选手已覆盖检查点数量
 * @param missingCheckpoints    缺失检查点代码，按检查点顺序排列；无缺失为空列表
 * @param factorP               让分系数分子 p（约分后）；非 HANDICAP 或未登记为 null
 * @param factorQ               让分系数分母 q（约分后）；非 HANDICAP 或未登记为 null
 * @param effectiveNum          HANDICAP 有效成绩精确分子（约分后）；未按让分排名为 null
 * @param effectiveDen          HANDICAP 有效成绩精确分母（约分后）；未按让分排名为 null
 * @param effectiveDisplayMs    HANDICAP 有效成绩展示值（HALF_UP 整数毫秒）；未按让分排名为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultEntryResponse(
        String bib,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        int checkpointCount,
        int coveredCheckpointCount,
        List<String> missingCheckpoints,
        Integer factorP,
        Integer factorQ,
        Long effectiveNum,
        Long effectiveDen,
        Long effectiveDisplayMs
) {
}
