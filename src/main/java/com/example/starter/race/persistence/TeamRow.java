package com.example.starter.race.persistence;

import com.example.starter.race.domain.TeamLockStatus;

/**
 * race_team 表行记录。
 *
 * @param raceId        所属赛事ID
 * @param teamId        队伍ID，同一赛事内唯一
 * @param captainBib    队长参赛号
 * @param status        名单锁定状态
 * @param rosterVersion 当前名单版本，0表示从未锁定
 * @param createdAt     创建时间，Unix毫秒时间戳
 * @param updatedAt     最近一次名单变更时间，Unix毫秒时间戳
 */
public record TeamRow(
        String raceId,
        String teamId,
        String captainBib,
        TeamLockStatus status,
        int rosterVersion,
        long createdAt,
        long updatedAt
) {
}
