package com.example.starter.race.api;

import com.example.starter.race.domain.RaceMode;
import com.example.starter.race.domain.RaceStatus;

/**
 * 赛事信息响应。
 *
 * @param raceId    赛事ID
 * @param version   当前版本号（从1开始）
 * @param status    OPEN / SEALED
 * @param mode      赛事模式：STANDARD / PURSUIT
 * @param createdAt 创建时间，Unix毫秒时间戳
 */
public record RaceResponse(String raceId, int version, RaceStatus status, RaceMode mode, long createdAt) {
}
