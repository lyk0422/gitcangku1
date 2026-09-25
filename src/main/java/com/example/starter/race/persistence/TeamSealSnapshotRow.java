package com.example.starter.race.persistence;

import java.util.List;

/**
 * team_seal_snapshot 表行记录（封榜时固化的队伍成绩快照）。
 *
 * @param raceId        所属快照的赛事ID
 * @param teamId        队伍ID
 * @param rosterVersion 封榜时固化的名单版本
 * @param resultVersion 封榜时的个人成绩版本（即封榜后的赛事版本）
 * @param teamScoreMs   团队得分=全部成员总耗时之和（毫秒）；存在未排名成员时为 null
 * @param teamRank      团队名次；不完整队伍为 null
 * @param memberCount   锁定名单人数
 * @param complete      封榜时全部成员均为 RANKED
 * @param sealedAt      封榜时间，Unix毫秒时间戳
 * @param members       封榜固化的锁定名单成员（按参赛号字典序）
 */
public record TeamSealSnapshotRow(
        String raceId,
        String teamId,
        int rosterVersion,
        int resultVersion,
        Long teamScoreMs,
        Integer teamRank,
        int memberCount,
        boolean complete,
        long sealedAt,
        List<String> members
) {
}
