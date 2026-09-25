package com.example.starter.race.persistence;

/**
 * result_snapshot_team 表行记录（封榜时固化的团队成绩快照）。
 *
 * @param raceId        所属快照的赛事ID
 * @param teamId        队伍ID
 * @param rosterVersion 封榜时固化的名单版本
 * @param raceVersion   封榜时固化的个人成绩版本（封榜后的赛事版本）
 * @param memberCount   封榜时锁定名单人数
 * @param rankedCount   封榜时 RANKED 成员人数
 * @param totalTimeMs   封榜时固化的团队得分（毫秒）；存在未排名成员为 null
 */
public record SnapshotTeamRow(
        String raceId,
        String teamId,
        int rosterVersion,
        int raceVersion,
        int memberCount,
        int rankedCount,
        Long totalTimeMs) {
}
