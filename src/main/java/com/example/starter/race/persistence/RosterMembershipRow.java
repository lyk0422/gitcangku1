package com.example.starter.race.persistence;

/**
 * roster_membership 表行记录：成员在赛事内的当前团队归属。
 *
 * <p>每个参赛号在同一赛事内至多归属一个团队（唯一键 race_id+bib）；
 * 成员一经锁定即不可更换团队，也不删除，退赛只改 active 标志与关联退赛记录。
 *
 * @param raceId        所属赛事ID
 * @param bib           参赛号
 * @param teamId        当前归属团队ID
 * @param rosterVersion 首次锁定进队的名单版本号
 * @param active        是否在队：TRUE-在队，FALSE-已登记退赛
 */
public record RosterMembershipRow(
        String raceId,
        String bib,
        String teamId,
        int rosterVersion,
        boolean active
) {
}
