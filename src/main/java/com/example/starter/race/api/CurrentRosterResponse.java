package com.example.starter.race.api;

import java.util.List;

/**
 * 团队当前名单响应：反映最新锁定版本叠加退赛等后续变化；
 * 与“锁定版本（不可变快照）”“已封榜团队成绩（只读）”相区分。
 *
 * @param raceId           赛事ID
 * @param teamId           团队ID
 * @param currentVersionNo 当前最新锁定名单版本号；团队从未锁定名单时为 0
 * @param members          当前成员，按参赛号字典序排列；含已退赛成员（state=WITHDRAWN，不删人）
 */
public record CurrentRosterResponse(
        String raceId,
        String teamId,
        int currentVersionNo,
        List<CurrentRosterMemberResponse> members
) {
}
