package com.example.starter.race.persistence;

/**
 * race_team_member 表行记录（队伍当前名单）。
 *
 * @param raceId    所属赛事ID
 * @param teamId    所属队伍ID
 * @param bib       成员参赛号；同一参赛者在同一赛事最多属于一支队伍
 * @param createdAt 加入时间，Unix毫秒时间戳
 */
public record TeamMemberRow(String raceId, String teamId, String bib, long createdAt) {
}
