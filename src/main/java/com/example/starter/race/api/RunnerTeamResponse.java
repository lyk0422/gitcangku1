package com.example.starter.race.api;

/**
 * 个人归属查询响应。
 *
 * @param raceId        所属赛事ID
 * @param bib           参赛者参赛号
 * @param teamId        所属队伍ID；未入队为 null
 * @param rosterVersion 所属队伍当前名单版本；未入队为 null
 * @param locked        所属队伍当前是否处于锁定状态；未入队为 null
 */
public record RunnerTeamResponse(
        String raceId,
        String bib,
        String teamId,
        Integer rosterVersion,
        Boolean locked
) {
}
