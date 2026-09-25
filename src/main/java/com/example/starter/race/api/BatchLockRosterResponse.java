package com.example.starter.race.api;

import java.util.List;

/**
 * 批量锁定结果：一事务写入的全部锁定快照。
 *
 * @param raceId      所属赛事ID
 * @param raceVersion 批量锁定完成后的赛事版本
 * @param locks       各队伍的锁定结果（按请求顺序）
 */
public record BatchLockRosterResponse(
        String raceId,
        int raceVersion,
        List<RosterLockResponse> locks
) {
}
