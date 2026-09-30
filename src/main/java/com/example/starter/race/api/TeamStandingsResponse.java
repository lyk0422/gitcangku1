package com.example.starter.race.api;

import com.example.starter.race.domain.RaceStatus;

import java.util.List;

/**
 * 团队成绩榜响应：OPEN 时按各团队当前最新锁定名单版本实时汇总（剔除已退赛成员）；
 * SEALED 时返回封榜只读团队榜（每个团队引用封榜时刻一个完整名单版本）。
 *
 * @param raceId   赛事ID
 * @param version  榜单对应的赛事版本号
 * @param status   OPEN / SEALED
 * @param teams    团队榜条目，按名次、团队ID字典序排列
 */
public record TeamStandingsResponse(
        String raceId,
        int version,
        RaceStatus status,
        List<TeamStandingResponse> teams
) {

    /**
     * 团队榜单个条目。
     *
     * @param teamId          团队ID
     * @param rosterVersionNo 引用的完整名单版本号
     * @param teamPoints      团队积分（剔除退赛成员后）
     * @param memberCount     计分成员人数
     * @param rank            团队名次，从1开始，积分并列同名次并跳号
     */
    public record TeamStandingResponse(
            String teamId,
            int rosterVersionNo,
            int teamPoints,
            int memberCount,
            int rank
    ) {
    }
}
