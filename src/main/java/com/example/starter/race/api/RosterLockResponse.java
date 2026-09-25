package com.example.starter.race.api;

import java.util.List;

/**
 * 名单锁定结果。
 *
 * @param raceId        所属赛事ID
 * @param teamId        队伍ID
 * @param rosterVersion 本次锁定生成的名单版本（从1开始，重锁加一）
 * @param raceVersion   锁定时的赛事版本（个人成绩版本）
 * @param members       固化的锁定名单（按参赛号字典序）
 * @param lockedAt      锁定时间，Unix毫秒时间戳
 * @param rosterKey     本次锁定的幂等指纹（队长+赛事版本+队伍+规范化成员集合）
 */
public record RosterLockResponse(
        String raceId,
        String teamId,
        int rosterVersion,
        int raceVersion,
        List<String> members,
        long lockedAt,
        String rosterKey
) {
}
