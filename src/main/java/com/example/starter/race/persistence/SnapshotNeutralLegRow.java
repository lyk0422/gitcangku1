package com.example.starter.race.persistence;

/**
 * result_snapshot_neutral_leg 表行记录（封榜时冻结的单选手单区间扣时明细）。
 *
 * @param raceId             所属快照的赛事ID
 * @param bib                参赛号
 * @param legIndex           中立区间序号，与 neutral_leg 一致
 * @param startPosition      冻结的起点检查点顺序；0表示发枪零点
 * @param endPosition        冻结的终点检查点顺序
 * @param startElapsedMillis 封榜时起点累计耗时（毫秒）；起点为发枪零点固化为0，端点缺失为 null
 * @param endElapsedMillis   封榜时终点累计耗时（毫秒）；端点缺失为 null
 * @param deductionMs        该区间扣时=终点-起点累计耗时（毫秒）；任一端点缺失为 null，不做估计扣除
 */
public record SnapshotNeutralLegRow(
        String raceId,
        String bib,
        int legIndex,
        int startPosition,
        int endPosition,
        Long startElapsedMillis,
        Long endElapsedMillis,
        Long deductionMs
) {
}
