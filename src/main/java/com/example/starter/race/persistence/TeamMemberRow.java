package com.example.starter.race.persistence;

/**
 * team_member 表行记录；同一选手在同一赛事最多属于一队（主键 race_id+bib 保证）。
 *
 * @param raceId    所属赛事ID
 * @param teamCode  所属团队代码
 * @param bib       成员参赛号
 * @param createdAt 配置时间，Unix毫秒时间戳
 */
public record TeamMemberRow(
        String raceId,
        String teamCode,
        String bib,
        long createdAt
) {
}
