package com.example.starter.race.persistence;

/**
 * relay_team_leg 表行记录：队伍某棒次登记的选手，顺序固定，同队同棒次仅一人。
 *
 * @param raceId  所属赛事ID
 * @param teamKey 队伍标识
 * @param legNo   棒次序号，从1开始
 * @param runner  该棒次登记选手标识
 */
public record RelayTeamLegRow(String raceId, String teamKey, int legNo, String runner) {
}
