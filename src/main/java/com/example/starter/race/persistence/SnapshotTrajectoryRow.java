package com.example.starter.race.persistence;

/**
 * result_snapshot_trajectory 表行记录：封榜时固化的轨迹完整采样点。
 *
 * @param raceId            所属快照的赛事ID
 * @param bib               选手参赛号
 * @param trajectoryVersion 封榜时轨迹版本
 * @param seq               采样序号，从0开始
 * @param elapsedMs         从起跑起的累计毫秒（非负整数）
 * @param progressMm        累计前进毫米（非负整数）
 */
public record SnapshotTrajectoryRow(
        String raceId,
        String bib,
        int trajectoryVersion,
        int seq,
        long elapsedMs,
        long progressMm) {
}
