package com.example.starter.race.api;

import java.util.List;

/**
 * 批量名单锁定响应。
 *
 * @param raceId  所属赛事ID
 * @param version 锁定完成后的赛事版本
 * @param locks   各队伍写入的锁定快照（按队伍ID字典序）
 */
public record LockRostersResponse(
        String raceId,
        int version,
        List<RosterLockItem> locks
) {

    /**
     * 单支队伍的锁定结果。
     *
     * @param teamId        队伍ID
     * @param rosterVersion 本次锁定生成的名单版本
     * @param captainBib    队长参赛号
     * @param members       锁定名单成员（规范化后按字典序）
     * @param raceVersion   锁定完成后的赛事版本
     * @param lockedAt      锁定时间，Unix毫秒时间戳
     */
    public record RosterLockItem(
            String teamId,
            int rosterVersion,
            String captainBib,
            List<String> members,
            int raceVersion,
            long lockedAt
    ) {
    }
}
