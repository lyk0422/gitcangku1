package com.example.starter.race.api;

import com.example.starter.race.domain.TeamLockStatus;

/**
 * 队伍响应。
 *
 * @param raceId        所属赛事ID
 * @param teamId        队伍ID
 * @param captainBib    队长参赛号
 * @param status        名单锁定状态
 * @param rosterVersion 当前名单版本，0表示从未锁定
 * @param raceVersion   响应对应的赛事版本
 * @param createdAt     队伍创建时间，Unix毫秒时间戳
 */
public record TeamResponse(
        String raceId,
        String teamId,
        String captainBib,
        TeamLockStatus status,
        int rosterVersion,
        int raceVersion,
        long createdAt
) {
}
