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
 * @param penaltyMs             生效加时合计毫秒数
 * @param totalTimeMs           总耗时毫秒；未排名为 null
 * @param checkpointCount       赛事检查点总数；未配置检查点为 0
 * @param coveredCheckpointCount 该选手已覆盖检查点数量
 * @param missingCheckpoints    缺失检查点代码，按检查点顺序排列；无缺失为空列表
 * @param appealPending         该选手是否存在待决（PENDING）处罚申诉；申诉冻结期间榜单仍按原处罚计算
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
        boolean appealPending
) {

    /** 无待决申诉场景的兼容构造器：appealPending=false。 */
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
        this(bib, rank, status, finishTimeMs, penaltyMs, totalTimeMs, checkpointCount,
                coveredCheckpointCount, missingCheckpoints, false);
    }
}
