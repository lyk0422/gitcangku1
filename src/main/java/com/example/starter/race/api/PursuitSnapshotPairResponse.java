package com.example.starter.race.api;

/**
 * 封榜冻结的追逐对与交会分数。
 *
 * @param pairNo                  追逐对序号
 * @param chaserBib               追赶者参赛号
 * @param chasedBib               被追者参赛号
 * @param chaserStartMm           追赶者起始距离（毫米）
 * @param chasedStartMm           被追者起始距离（毫米）
 * @param chaserTrajectoryVersion 封榜时裁定依据的追赶者轨迹版本
 * @param chasedTrajectoryVersion 封榜时裁定依据的被追者轨迹版本
 * @param catchTimeNum            冻结的最早交会时刻（毫秒）有理数分子，已约分
 * @param catchTimeDen            冻结的最早交会时刻（毫秒）有理数分母，已约分
 */
public record PursuitSnapshotPairResponse(
        int pairNo,
        String chaserBib,
        String chasedBib,
        long chaserStartMm,
        long chasedStartMm,
        int chaserTrajectoryVersion,
        int chasedTrajectoryVersion,
        long catchTimeNum,
        long catchTimeDen
) {
}
