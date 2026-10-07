package com.example.starter.race.persistence;

import com.example.starter.race.domain.RulingStatus;

/**
 * pursuit_ruling 表行记录：追逐对显式裁定（含历史）。
 *
 * @param rulingId                  裁定ID，全局唯一
 * @param raceId                    所属赛事ID
 * @param pairId                    追逐对ID
 * @param pursuerTrajectoryVersion  裁定时追赶者轨迹版本
 * @param pursuedTrajectoryVersion  裁定时被追者轨迹版本
 * @param catchTimeNum              最早交会时刻（从起跑起毫秒）精确有理数分子，已约分
 * @param catchTimeDen              最早交会时刻精确有理数分母（正整数），已约分
 * @param status                    裁定状态：CURRENT-当前有效，SUPERSEDED-因轨迹更正被取代
 * @param operator                  裁定操作者
 * @param createdAt                 裁定时间，Unix毫秒时间戳
 */
public record PursuitRulingRow(
        String rulingId,
        String raceId,
        String pairId,
        int pursuerTrajectoryVersion,
        int pursuedTrajectoryVersion,
        String catchTimeNum,
        String catchTimeDen,
        RulingStatus status,
        String operator,
        long createdAt) {
}
