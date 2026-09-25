package com.example.starter.race.api;

import com.example.starter.race.domain.TeamLockStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 队伍名单查询响应：当前名单、名单版本与最近一次锁定/解锁信息。
 *
 * @param raceId          所属赛事ID
 * @param teamId          队伍ID
 * @param captainBib      队长参赛号
 * @param status          当前锁定状态
 * @param rosterVersion   当前名单版本，0表示从未锁定
 * @param members         当前名单成员（按参赛号字典序）
 * @param raceVersion     响应对应的赛事版本
 * @param lockedAt        当前生效锁定的锁定时间；未锁定为 null
 * @param lockRaceVersion 当前生效锁定对应的赛事版本；未锁定为 null
 * @param unlockedAt      最近一次解锁时间；从未解锁为 null
 * @param unlockReason    最近一次解锁原因；从未解锁为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RosterResponse(
        String raceId,
        String teamId,
        String captainBib,
        TeamLockStatus status,
        int rosterVersion,
        List<String> members,
        int raceVersion,
        Long lockedAt,
        Integer lockRaceVersion,
        Long unlockedAt,
        String unlockReason
) {
}
