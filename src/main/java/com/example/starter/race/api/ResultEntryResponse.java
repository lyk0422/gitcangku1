package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 成绩榜中的单个条目。
 *
 * @param bib                    参赛号
 * @param rank                   名次（单值模式，并列同名次并跳号）；非 RANKED 或区间模式为 null
 * @param status                 RANKED / UNTIMED / MISSING_CHECKPOINT / DISQUALIFIED
 * @param finishTimeMs           原始完赛耗时毫秒（单值模式）；计时缺失或区间模式为 null
 * @param penaltyMs              生效加时合计毫秒数
 * @param totalTimeMs            总耗时毫秒（单值模式）；未排名或区间模式为 null
 * @param checkpointCount        赛事检查点总数；未配置检查点为 0
 * @param coveredCheckpointCount 该选手已覆盖检查点数量
 * @param missingCheckpoints     缺失检查点代码，按检查点顺序排列；无缺失为空列表
 * @param rankBest               区间模式：保证最好名次；单值模式或非完整选手为 null
 * @param rankWorst              区间模式：可能最差名次；单值模式或非完整选手为 null
 * @param finishLowerMs          区间模式：原始完赛区间下界毫秒；无完赛区间为 null
 * @param finishUpperMs          区间模式：原始完赛区间上界毫秒；无完赛区间为 null
 * @param netLowerMs             区间模式：净区间下界毫秒；非 RANKED 为 null
 * @param netUpperMs             区间模式：净区间上界毫秒；非 RANKED 为 null
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
        Integer rankBest,
        Integer rankWorst,
        Long finishLowerMs,
        Long finishUpperMs,
        Long netLowerMs,
        Long netUpperMs
) {

    /** 单值模式构造器：区间字段均为 null。 */
    public ResultEntryResponse(
            String bib,
            Integer rank,
            EntryStatus status,
            Long finishTimeMs,
            long penaltyMs,
            Long totalTimeMs,
            int checkpointCount,
            int coveredCheckpointCount,
            List<String> missingCheckpoints) {
        this(bib, rank, status, finishTimeMs, penaltyMs, totalTimeMs,
                checkpointCount, coveredCheckpointCount, missingCheckpoints,
                null, null, null, null, null, null);
    }
}
