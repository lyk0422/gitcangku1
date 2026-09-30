package com.example.starter.race.api;

import com.example.starter.race.domain.RaceStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 赛事信息响应。
 *
 * @param raceId        赛事ID
 * @param version       当前版本号（从1开始）
 * @param status        OPEN / SEALED
 * @param depositAmount 申诉保证金当前配置金额（人民币元，正整数）；尚未配置为 null
 * @param createdAt     创建时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RaceResponse(
        String raceId,
        int version,
        RaceStatus status,
        Integer depositAmount,
        long createdAt
) {
}
