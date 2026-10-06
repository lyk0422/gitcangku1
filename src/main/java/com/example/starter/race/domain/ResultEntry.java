package com.example.starter.race.domain;

import java.util.List;

/**
 * 单个选手的成绩条目。
 *
 * @param bib                     参赛号
 * @param rank                    名次，从1开始；并列同名次且跳号（1、1、3）；非 RANKED 为 null
 * @param status                  成绩状态
 * @param finishTimeMs            原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs               全部未撤销加时处罚的合计毫秒数；无加时为 0
 * @param totalTimeMs             总耗时展示值（毫秒）：标准赛=原始完赛耗时+生效加时；
 *                                让分赛=精确分数 HALF_UP 舍入到整数毫秒；未排名时为 null
 * @param checkpointCount         赛事配置的检查点总数；未配置检查点的赛事为 0
 * @param coveredCheckpointCount  该选手已有分段记录的检查点数量
 * @param missingCheckpoints      该选手尚未通过的检查点代码，按检查点顺序排列；全部覆盖或无检查点时为空列表
 * @param factorP                 让分系数分子 p（约分后，1~1000）；非让分赛或未登记为 null
 * @param factorQ                 让分系数分母 q（约分后，1~1000）；非让分赛或未登记为 null
 * @param scoreNumerator          让分赛精确有效成绩的分子（约分后）；仅让分赛 RANKED 条目非 null
 * @param scoreDenominator        让分赛精确有效成绩的分母（约分后）；仅让分赛 RANKED 条目非 null
 */
public record ResultEntry(
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
        Long scoreNumerator,
        Long scoreDenominator
) {

    /** 未配置检查点赛事使用的兼容构造器：检查点计数均为 0、缺失列表为空、无让分字段。 */
    public ResultEntry(
            String bib,
            Integer rank,
            EntryStatus status,
            Long finishTimeMs,
            long penaltyMs,
            Long totalTimeMs) {
        this(bib, rank, status, finishTimeMs, penaltyMs, totalTimeMs, 0, 0, List.of(),
                null, null, null, null);
    }

    /** 标准计分赛事使用的兼容构造器：无让分字段。 */
    public ResultEntry(
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
                null, null, null, null);
    }
}
