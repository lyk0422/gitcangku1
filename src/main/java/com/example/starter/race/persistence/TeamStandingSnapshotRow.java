package com.example.starter.race.persistence;

/**
 * team_standing_snapshot 表行记录：封榜团队榜中单个团队的只读汇总。
 *
 * @param raceId          所属快照的赛事ID
 * @param teamId          团队ID
 * @param rosterVersionNo 封榜引用的完整名单版本号（该团队最新已锁定版本）
 * @param teamPoints      封榜时刻固化的团队积分（剔除当时已退赛成员）
 * @param memberCount     计入积分的成员人数（剔除退赛成员）
 * @param rankNo          团队名次，从1开始，积分并列同名次并跳号
 * @param displayOrder    展示顺序，从0开始
 */
public record TeamStandingSnapshotRow(
        String raceId,
        String teamId,
        int rosterVersionNo,
        int teamPoints,
        int memberCount,
        int rankNo,
        int displayOrder
) {
}
