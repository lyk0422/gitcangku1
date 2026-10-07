package com.example.starter.race.persistence;

/**
 * trajectory 表行记录：选手直线进度轨迹头（当前版本）。
 *
 * @param raceId      所属赛事ID
 * @param bib         选手参赛号
 * @param version     轨迹版本，从1开始，每次采样提交/更正加一
 * @param sampleCount 当前版本采样点数（1~10）
 * @param operator    最近一次提交操作者
 * @param updatedAt   最近一次提交时间，Unix毫秒时间戳
 */
public record TrajectoryRow(
        String raceId,
        String bib,
        int version,
        int sampleCount,
        String operator,
        long updatedAt) {
}
