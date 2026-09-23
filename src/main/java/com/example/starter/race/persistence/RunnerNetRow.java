package com.example.starter.race.persistence;

/**
 * runner_net 表行记录（选手净完赛口径，每次恢复一致重算后覆盖）。
 *
 * @param raceId              所属赛事ID
 * @param bib                 选手参赛号
 * @param netFinishTimeMs     净完赛耗时（毫秒）；计时缺失为 null
 * @param finishCompensationMs 完赛口径累计补偿毫秒数
 * @param updatedAt           最近一次净值重算时间，Unix毫秒时间戳
 */
public record RunnerNetRow(
        String raceId,
        String bib,
        Long netFinishTimeMs,
        long finishCompensationMs,
        long updatedAt
) {
}
