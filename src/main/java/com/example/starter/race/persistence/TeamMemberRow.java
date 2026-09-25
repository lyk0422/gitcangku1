package com.example.starter.race.persistence;

/**
 * team_member 表行记录（当前名单；锁定后的历史名单见 roster_lock_member）。
 *
 * @param raceId  所属赛事ID
 * @param teamId  所属队伍ID
 * @param bib     成员参赛号；同一赛事内一名参赛者最多属于一支队伍
 * @param addedAt 加入时间，Unix毫秒时间戳
 */
public record TeamMemberRow(String raceId, String teamId, String bib, long addedAt) {
}
