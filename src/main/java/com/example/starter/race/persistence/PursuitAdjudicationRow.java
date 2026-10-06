package com.example.starter.race.persistence;

/**
 * pursuit_adjudication 表行记录：每个追逐对仅保留一条当前裁定。
 *
 * @param raceId                  所属赛事ID
 * @param pairNo                  追逐对序号
 * @param chaserBib               追赶者参赛号（裁定时的快照）
 * @param chasedBib               被追者参赛号（裁定时的快照）
 * @param chaserTrajectoryVersion 裁定依据的追赶者轨迹版本
 * @param chasedTrajectoryVersion 裁定依据的被追者轨迹版本
 * @param catchTimeNum            最早交会时刻（毫秒）精确有理数分子，已约分
 * @param catchTimeDen            最早交会时刻（毫秒）精确有理数分母，已约分，正整数
 * @param createdAt               裁定时间，Unix毫秒时间戳
 * @param createdBy               裁定操作者
 */
public record PursuitAdjudicationRow(
        String raceId,
        int pairNo,
        String chaserBib,
        String chasedBib,
        int chaserTrajectoryVersion,
        int chasedTrajectoryVersion,
        long catchTimeNum,
        long catchTimeDen,
        long createdAt,
        String createdBy) {
}
