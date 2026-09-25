package com.example.starter.race.api;

import com.example.starter.race.domain.TeamStatus;

import java.util.List;

/**
 * 队伍名单查询响应：当前状态、名单版本与全部锁定历史。
 *
 * @param raceId        所属赛事ID
 * @param teamId        队伍ID
 * @param captainBib    队长参赛号
 * @param status        名单状态：OPEN-可编辑，LOCKED-已锁定
 * @param rosterVersion 当前名单版本；从未锁定为0
 * @param members       当前名单成员（按参赛号字典序）
 * @param locks         全部锁定版本历史（按版本升序，含已解锁）
 */
public record TeamRosterResponse(
        String raceId,
        String teamId,
        String captainBib,
        TeamStatus status,
        int rosterVersion,
        List<String> members,
        List<RosterLockInfo> locks
) {

    /**
     * 一个名单锁定版本的历史信息。
     *
     * @param rosterVersion 名单版本
     * @param raceVersion   锁定时的赛事版本
     * @param lockedBy      提交锁定的队长参赛号
     * @param lockedAt      锁定时间，Unix毫秒时间戳
     * @param members       该版本固化的名单
     * @param unlocked      是否已被裁判解锁
     * @param unlockReason  解锁原因；未解锁为 null
     * @param unlockedAt    解锁时间，Unix毫秒时间戳；未解锁为 null
     */
    public record RosterLockInfo(
            int rosterVersion,
            int raceVersion,
            String lockedBy,
            long lockedAt,
            List<String> members,
            boolean unlocked,
            String unlockReason,
            Long unlockedAt
    ) {
    }
}
