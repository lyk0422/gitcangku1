package com.example.starter.race.persistence;

import java.util.List;

/**
 * team_roster 与 team_roster_member 合成的一个已锁定名单版本。
 *
 * @param raceId      所属赛事ID
 * @param teamId      团队ID
 * @param versionNo   名单版本号，团队内从1连续递增
 * @param lockedAt    锁定时间，Unix毫秒时间戳
 * @param teamPoints  锁定时刻按固化成员成绩汇总的团队积分（未剔退赛）
 * @param members     该版本固化的成员成绩归属，按 display_order 排列；退赛不删除
 */
public record TeamRosterRow(
        String raceId,
        String teamId,
        int versionNo,
        long lockedAt,
        int teamPoints,
        List<TeamRosterMemberRow> members
) {
}
