package com.example.starter.race.persistence;

import com.example.starter.race.domain.TeamLockStatus;

/**
 * team_roster_lock 表行记录（名单锁定快照，历史版本保留）。
 *
 * @param raceId        所属赛事ID
 * @param teamId        队伍ID
 * @param rosterVersion 名单版本，从1开始
 * @param captainBib    锁定时队长参赛号
 * @param raceVersion   锁定完成后的赛事版本
 * @param lockedAt      锁定时间，Unix毫秒时间戳
 * @param status        LOCKED-生效中，UNLOCKED-已被裁判解锁
 * @param unlockedAt    解锁时间，Unix毫秒时间戳；未解锁为 null
 * @param unlockReason  裁判解锁原因；未解锁为 null
 */
public record RosterLockRow(
        String raceId,
        String teamId,
        int rosterVersion,
        String captainBib,
        int raceVersion,
        long lockedAt,
        TeamLockStatus status,
        Long unlockedAt,
        String unlockReason
) {
}
