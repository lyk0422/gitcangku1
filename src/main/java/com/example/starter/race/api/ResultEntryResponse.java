package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 成绩榜中的单个条目。
 *
 * @param bib                    参赛号
 * @param rank                   名次（并列同名次并跳号）；非 RANKED 为 null
 * @param status                RANKED / UNTIMED / MISSING_CHECKPOINT / DISQUALIFIED
 * @param finishTimeMs          原始完赛耗时毫秒；计时缺失为 null
 * @param penaltyMs             生效加时合计毫秒数（原罚时，不扣减执行量）
 * @param servedMs              已确认执行凭证抵扣合计毫秒数
 * @param totalTimeMs           总耗时毫秒（原始耗时+生效加时-已执行抵扣）；未排名为 null
 * @param checkpointCount       赛事检查点总数；未配置检查点为 0
 * @param coveredCheckpointCount 该选手已覆盖检查点数量
 * @param missingCheckpoints    缺失检查点代码，按检查点顺序排列；无缺失为空列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultEntryResponse(
        String bib,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        long servedMs,
        Long totalTimeMs,
        int checkpointCount,
        int coveredCheckpointCount,
        List<String> missingCheckpoints
) {
}