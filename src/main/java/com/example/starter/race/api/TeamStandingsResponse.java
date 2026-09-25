package com.example.starter.race.api;

import com.example.starter.race.domain.RaceStatus;

import java.util.List;

/**
 * 赛事团队得分汇总查询响应；封榜后返回封榜固化的团队快照。
 *
 * @param raceId  所属赛事ID
 * @param version 当前赛事版本（封榜后为封榜版本）
 * @param status  OPEN / SEALED
 * @param teams   各锁定队伍的团队得分（按队伍ID字典序）
 */
public record TeamStandingsResponse(
        String raceId,
        int version,
        RaceStatus status,
        List<TeamStandingResponse> teams
) {
}
