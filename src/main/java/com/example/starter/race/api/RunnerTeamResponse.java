package com.example.starter.race.api;

import com.example.starter.race.domain.TeamStatus;

/**
 * 参赛者队伍归属查询响应；未加入任何队伍时 teamId 为 null。
 *
 * @param raceId        所属赛事ID
 * @param bib           参赛者号码
 * @param teamId        所属队伍ID；未加入为 null
 * @param teamStatus    所属队伍名单状态；未加入为 null
 * @param rosterVersion 所属队伍当前名单版本；未加入为 null
 */
public record RunnerTeamResponse(
        String raceId,
        String bib,
        String teamId,
        TeamStatus teamStatus,
        Integer rosterVersion
) {
}
