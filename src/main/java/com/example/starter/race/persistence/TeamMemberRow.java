package com.example.starter.race.persistence;

/**
 * team_member 表行记录（团队成员配置）。
 *
 * @param raceId 所属赛事ID
 * @param teamId 所属团队代码
 * @param bib    成员参赛号，同一赛事内只能属于一个团队
 */
public record TeamMemberRow(String raceId, String teamId, String bib) {
}
