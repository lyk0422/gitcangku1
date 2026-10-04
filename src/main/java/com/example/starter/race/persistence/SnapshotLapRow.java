package com.example.starter.race.persistence;

import com.example.starter.race.domain.LapPoint;

/**
 * result_snapshot_lap 表行记录（绕圈赛事封榜时固化的圈次当前记录）。
 *
 * @param raceId        所属快照的赛事ID
 * @param bib           参赛号
 * @param lapNumber     圈号
 * @param point         圈内点位：A/B/FINISH
 * @param elapsedMillis 封榜时该点的当前读数（毫秒）
 * @param timingId      封榜时的当前记录ID
 */
public record SnapshotLapRow(
        String raceId,
        String bib,
        int lapNumber,
        LapPoint point,
        long elapsedMillis,
        String timingId
) {
}