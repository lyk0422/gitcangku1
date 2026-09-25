package com.example.starter.race.persistence;

/**
 * race_start 起跑记录表行记录；同一选手同一赛事最多一条成功起跑。
 *
 * @param startId   起跑记录业务键，全局唯一
 * @param raceId    所属赛事ID
 * @param bib       起跑选手参赛号
 * @param startedAt 起跑提交时刻，Unix毫秒时间戳
 */
public record RaceStartRow(
        String startId,
        String raceId,
        String bib,
        long startedAt
) {
}
