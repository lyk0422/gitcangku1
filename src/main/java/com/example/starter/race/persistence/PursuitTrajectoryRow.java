package com.example.starter.race.persistence;

/**
 * pursuit_trajectory 表行记录。
 *
 * @param raceId    所属赛事ID
 * @param bib       选手参赛号
 * @param version   轨迹版本号，首次采样为1，每次更正加一
 * @param updatedAt 最近一次采样/更正时间，Unix毫秒时间戳
 * @param updatedBy 最近一次采样/更正操作者
 */
public record PursuitTrajectoryRow(
        String raceId,
        String bib,
        int version,
        long updatedAt,
        String updatedBy) {
}
