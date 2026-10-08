package com.example.starter.race.domain;

import java.util.List;

/**
 * 区间计时模式下单个选手的成绩条目。
 *
 * @param bib                     参赛号
 * @param status                  成绩状态（RANKED 仅表示完整选手参与名次范围计算，不含确定名次）
 * @param rankBest                保证最好名次=1+肯定早于自己的人数；非 RANKED 为 null
 * @param rankWorst               可能最差名次=完整人数-自己肯定早于的人数；非 RANKED 为 null
 * @param finishLowerMs           原始完赛区间下界（毫秒）；无完赛区间为 null
 * @param finishUpperMs           原始完赛区间上界（毫秒）；无完赛区间为 null
 * @param penaltyMs               全部未撤销加时处罚的合计毫秒数；无加时为 0
 * @param netLowerMs              净区间下界=完赛区间下界+生效加时；非 RANKED 为 null
 * @param netUpperMs              净区间上界=完赛区间上界+生效加时；非 RANKED 为 null
 * @param checkpointCount         赛事配置的检查点总数；未配置检查点的赛事为 0
 * @param coveredCheckpointCount  该选手已有区间记录的检查点数量
 * @param missingCheckpoints      该选手尚未提交区间的检查点代码，按检查点顺序排列；无缺失为空列表
 */
public record IntervalResultEntry(
        String bib,
        EntryStatus status,
        Integer rankBest,
        Integer rankWorst,
        Long finishLowerMs,
        Long finishUpperMs,
        long penaltyMs,
        Long netLowerMs,
        Long netUpperMs,
        int checkpointCount,
        int coveredCheckpointCount,
        List<String> missingCheckpoints
) {
}
