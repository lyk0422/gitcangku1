package com.example.starter.race.api;

import com.example.starter.race.domain.RulingStatus;

/**
 * 追逐对裁定响应；最早交会时刻以精确有理数（已约分）表示，不取整。
 *
 * @param rulingId                  裁定ID
 * @param raceId                    赛事ID
 * @param pairId                    追逐对ID
 * @param pursuerBib                追赶者参赛号
 * @param pursuedBib                被追者参赛号
 * @param pursuerTrajectoryVersion  裁定时追赶者轨迹版本
 * @param pursuedTrajectoryVersion  裁定时被追者轨迹版本
 * @param catchTimeNum              最早交会时刻（从起跑起毫秒）有理数分子，十进制字符串
 * @param catchTimeDen              最早交会时刻有理数分母（正整数），十进制字符串
 * @param status                    裁定状态：CURRENT / SUPERSEDED
 * @param createdAt                 裁定时间，Unix毫秒时间戳
 */
public record RulingResponse(
        String rulingId,
        String raceId,
        String pairId,
        String pursuerBib,
        String pursuedBib,
        int pursuerTrajectoryVersion,
        int pursuedTrajectoryVersion,
        String catchTimeNum,
        String catchTimeDen,
        RulingStatus status,
        long createdAt
) {
}
