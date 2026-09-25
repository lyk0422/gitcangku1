package com.example.starter.race.persistence;

/**
 * relay_team 表行记录：一支接力参赛队伍。
 *
 * @param id        自增主键
 * @param raceId    所属接力赛事ID
 * @param teamKey   队伍标识，赛事内唯一
 * @param createdAt 队伍登记时间，Unix毫秒时间戳
 */
public record RelayTeamRow(
        long id,
        String raceId,
        String teamKey,
        long createdAt) {
}
