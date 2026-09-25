package com.example.starter.race.api;

import com.example.starter.race.domain.TeamStatus;

import java.util.List;

/**
 * 队伍信息响应（写操作返回最新状态）。
 *
 * @param raceId        所属赛事ID
 * @param teamId        队伍ID
 * @param captainBib    队长参赛号
 * @param status        名单状态：OPEN-可编辑，LOCKED-已锁定
 * @param rosterVersion 当前名单版本；从未锁定为0
 * @param raceVersion   操作完成后的赛事版本
 * @param members       当前名单成员（按参赛号字典序）
 */
public record TeamResponse(
        String raceId,
        String teamId,
        String captainBib,
        TeamStatus status,
        int rosterVersion,
        int raceVersion,
        List<String> members
) {
}
