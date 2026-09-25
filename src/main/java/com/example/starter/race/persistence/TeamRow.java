package com.example.starter.race.persistence;

import com.example.starter.race.domain.TeamStatus;

/**
 * team 表行记录。
 *
 * @param raceId        所属赛事ID
 * @param teamId        队伍ID，同一赛事内唯一
 * @param captainBib    队长参赛号
 * @param status        名单状态：OPEN-可编辑，LOCKED-已锁定
 * @param rosterVersion 当前名单版本，每次锁定加一；从未锁定为0
 * @param createdAt     创建时间，Unix毫秒时间戳
 * @param updatedAt     最近一次名单变更时间，Unix毫秒时间戳
 */
public record TeamRow(
        String raceId,
        String teamId,
        String captainBib,
        TeamStatus status,
        int rosterVersion,
        long createdAt,
        long updatedAt) {
}
