package com.example.starter.race.domain;

import java.util.List;

/**
 * 单个选手的成绩条目。
 *
 * @param bib                     参赛号
 * @param rank                    名次，从1开始；并列同名次且跳号（1、1、3）；非 RANKED 为 null
 * @param status                  成绩状态
 * @param finishTimeMs            原始完赛耗时（毫秒）；计时缺失为 null
 * @param netFinishTimeMs         净完赛耗时（毫秒）=原始完赛-完赛口径累计补偿；无中止事件时等于原始值
 * @param penaltyMs               全部未撤销加时处罚的合计毫秒数；无加时为 0
 * @param finishCompensationMs    完赛口径累计补偿毫秒数，未受影响为 0
 * @param totalTimeMs             总耗时=净完赛耗时+生效加时（毫秒）；未排名时为 null
 * @param checkpointCount         赛事配置的检查点总数；未配置检查点的赛事为 0
 * @param coveredCheckpointCount  该选手已有分段记录的检查点数量
 * @param missingCheckpoints      该选手尚未通过的检查点代码，按检查点顺序排列；全部覆盖或无检查点时为空列表
 */
public record ResultEntry(
        String bib,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        Long netFinishTimeMs,
        long penaltyMs,
        long finishCompensationMs,
        Long totalTimeMs,
        int checkpointCount,
        int coveredCheckpointCount,
        List<String> missingCheckpoints
) {

    /** 未配置检查点、无中止补偿时的兼容构造器：净完赛等于原始完赛、补偿为0。 */
    public ResultEntry(
            String bib,
            Integer rank,
            EntryStatus status,
            Long finishTimeMs,
            long penaltyMs,
            Long totalTimeMs) {
        this(bib, rank, status, finishTimeMs, finishTimeMs, penaltyMs, 0L, totalTimeMs, 0, 0,
                List.of());
    }
}
