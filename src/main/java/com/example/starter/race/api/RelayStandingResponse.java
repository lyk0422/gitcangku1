package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 接力排名响应：即时排名或封榜只读排名。
 *
 * @param raceId   赛事ID
 * @param version  榜单对应的版本号
 * @param status   OPEN / SEALED
 * @param legCount 棒次数
 * @param sealedAt 封榜时间，Unix毫秒时间戳；未封榜为 null
 * @param teams    按展示顺序排列的队伍排名条目
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelayStandingResponse(
        String raceId,
        int version,
        String status,
        int legCount,
        Long sealedAt,
        List<RelayRankEntryResponse> teams) {
}
