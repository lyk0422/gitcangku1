package com.example.starter.race.api;

/**
 * 追逐对裁定响应。
 *
 * @param raceId                  赛事ID
 * @param pairNo                  追逐对序号
 * @param chaserBib               追赶者参赛号
 * @param chasedBib               被追者参赛号
 * @param chaserTrajectoryVersion 裁定依据的追赶者轨迹版本
 * @param chasedTrajectoryVersion 裁定依据的被追者轨迹版本
 * @param catchTimeNum            最早交会时刻（起跑起毫秒）精确有理数分子，已约分
 * @param catchTimeDen            最早交会时刻（起跑起毫秒）精确有理数分母，已约分
 * @param createdAt               裁定时间，Unix毫秒时间戳
 */
public record AdjudicationResponse(
        String raceId,
        int pairNo,
        String chaserBib,
        String chasedBib,
        int chaserTrajectoryVersion,
        int chasedTrajectoryVersion,
        long catchTimeNum,
        long catchTimeDen,
        long createdAt
) {
}
