package com.example.starter.race.persistence;

/**
 * roster_lock 表行记录：团队名单的一个已锁定完整版本，创建后不可变。
 *
 * @param raceId        所属赛事ID
 * @param teamId        团队ID
 * @param rosterVersion 名单版本号，同一团队内从1连续递增
 * @param raceVersion   锁定该版本时推进后的赛事版本号
 * @param lockedAt      锁定时间，Unix毫秒时间戳
 */
public record RosterLockRow(
        String raceId,
        String teamId,
        int rosterVersion,
        int raceVersion,
        long lockedAt
) {
}
