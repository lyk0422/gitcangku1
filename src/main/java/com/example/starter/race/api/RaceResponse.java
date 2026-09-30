package com.example.starter.race.api;

import com.example.starter.race.domain.RaceStatus;

/**
 * 赛事信息响应。
 *
 * @param raceId     赛事ID
 * @param version    当前版本号（从1开始）
 * @param status     OPEN / SEALED
 * @param depositFen 申诉保证金当前配置金额，单位：分（1元=100分）；0表示未配置
 * @param createdAt  创建时间，Unix毫秒时间戳
 */
public record RaceResponse(
        String raceId,
        int version,
        RaceStatus status,
        long depositFen,
        long createdAt
) {
}
