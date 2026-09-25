package com.example.starter.race.persistence;

/**
 * roster_lock 表行记录（名单锁定快照头；解锁不删除，重锁生成新版本）。
 *
 * @param raceId        所属赛事ID
 * @param teamId        队伍ID
 * @param rosterVersion 名单版本，从1开始
 * @param raceVersion   锁定时的赛事版本（个人成绩版本）
 * @param lockedBy      提交锁定的队长参赛号
 * @param lockedAt      锁定时间，Unix毫秒时间戳
 * @param unlocked      是否已被裁判解锁
 * @param unlockReason  裁判解锁原因；未解锁为 null
 * @param unlockedAt    解锁时间，Unix毫秒时间戳；未解锁为 null
 */
public record RosterLockRow(
        String raceId,
        String teamId,
        int rosterVersion,
        int raceVersion,
        String lockedBy,
        long lockedAt,
        boolean unlocked,
        String unlockReason,
        Long unlockedAt) {
}
