package com.example.starter.race.api;

import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.TimingMode;

/**
 * 赛事信息响应。
 *
 * @param raceId     赛事ID
 * @param version    当前版本号（从1开始）
 * @param status     OPEN / SEALED
 * @param timingMode 计时模式：SINGLE-单值计时，INTERVAL-区间计时
 * @param createdAt  创建时间，Unix毫秒时间戳
 */
public record RaceResponse(
        String raceId,
        int version,
        RaceStatus status,
        TimingMode timingMode,
        long createdAt) {
}
