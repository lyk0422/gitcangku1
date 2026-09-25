package com.example.starter.race.api;

import com.example.starter.race.domain.RaceStatus;

/**
 * 赛事信息响应。
 *
 * @param raceId      赛事ID
 * @param version     当前版本号（从1开始）
 * @param status      OPEN / SEALED
 * @param baseStartMs 赛事基准（枪声）起跑时刻（Unix 毫秒时间戳，UTC）；创建时未设置为 null
 * @param createdAt   创建时间，Unix毫秒时间戳
 */
public record RaceResponse(
        String raceId,
        int version,
        RaceStatus status,
        Long baseStartMs,
        long createdAt
) {
}
