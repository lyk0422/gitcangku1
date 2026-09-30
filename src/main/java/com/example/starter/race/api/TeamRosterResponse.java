package com.example.starter.race.api;

import java.util.List;

/**
 * 一个已锁定名单版本的只读响应：成员成绩归属为锁定时刻快照，永不改写。
 *
 * @param raceId      赛事ID
 * @param teamId      团队ID
 * @param versionNo   名单版本号，团队内从1连续递增
 * @param lockedAt    锁定时间，Unix毫秒时间戳
 * @param teamPoints  锁定时刻固化的团队积分（未剔退赛；后续计分以当前名单/封榜榜为准）
 * @param members     固化成员，按参赛号字典序排列
 */
public record TeamRosterResponse(
        String raceId,
        String teamId,
        int versionNo,
        long lockedAt,
        int teamPoints,
        List<RosterMemberResponse> members
) {
}
