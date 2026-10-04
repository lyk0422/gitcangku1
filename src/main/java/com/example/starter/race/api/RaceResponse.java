package com.example.starter.race.api;

import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.RaceType;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 赛事信息响应。
 *
 * @param raceId    赛事ID
 * @param version   当前版本号（从1开始）
 * @param status    OPEN / SEALED
 * @param raceType  赛事类型：STANDARD / LAP
 * @param totalLaps 绕圈赛事总圈数；标准赛事为 null（响应中省略）
 * @param createdAt 创建时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RaceResponse(
        String raceId,
        int version,
        RaceStatus status,
        RaceType raceType,
        Integer totalLaps,
        long createdAt
) {
}