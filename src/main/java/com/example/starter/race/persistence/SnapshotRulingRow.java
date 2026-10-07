package com.example.starter.race.persistence;

/**
 * result_snapshot_ruling 表行记录：封榜时固化的追逐对当前裁定（交会分数）。
 *
 * @param raceId                    所属快照的赛事ID
 * @param pairId                    追逐对ID
 * @param rulingId                  封榜时当前裁定ID
 * @param pursuerTrajectoryVersion  裁定时追赶者轨迹版本
 * @param pursuedTrajectoryVersion  裁定时被追者轨迹版本
 * @param catchTimeNum              最早交会时刻精确有理数分子，已约分
 * @param catchTimeDen              最早交会时刻精确有理数分母（正整数），已约分
 */
public record SnapshotRulingRow(
        String raceId,
        String pairId,
        String rulingId,
        int pursuerTrajectoryVersion,
        int pursuedTrajectoryVersion,
        String catchTimeNum,
        String catchTimeDen) {
}
