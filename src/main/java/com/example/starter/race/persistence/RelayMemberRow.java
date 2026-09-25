package com.example.starter.race.persistence;

/**
 * relay_team_member 表行记录：队伍某棒次的登记选手，顺序固定。
 *
 * @param id        自增主键
 * @param raceId    所属接力赛事ID
 * @param teamKey   所属队伍标识
 * @param legNo     棒次序号，从1开始
 * @param bib       该棒次选手参赛号
 * @param createdAt 登记时间，Unix毫秒时间戳
 */
public record RelayMemberRow(
        long id,
        String raceId,
        String teamKey,
        int legNo,
        String bib,
        long createdAt) {
}
