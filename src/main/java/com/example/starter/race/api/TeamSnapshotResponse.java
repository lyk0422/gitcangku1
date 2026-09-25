package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 封榜队伍成绩快照响应：固化名单版本、个人成绩版本与团队得分。
 *
 * @param raceId        所属赛事ID
 * @param resultVersion 封榜时的个人成绩版本（即封榜后的赛事版本）
 * @param sealedAt      封榜时间，Unix毫秒时间戳
 * @param teams         固化的队伍快照（完整队伍按名次在前）
 */
public record TeamSnapshotResponse(
        String raceId,
        int resultVersion,
        long sealedAt,
        List<TeamSnapshotEntry> teams
) {

    /**
     * 单支队伍的封榜快照。
     *
     * @param teamId        队伍ID
     * @param rosterVersion 固化的名单版本
     * @param members       固化的锁定名单成员（按参赛号字典序）
     * @param complete      封榜时全部成员均为 RANKED
     * @param teamScoreMs   固化的团队得分（毫秒）；不完整为 null
     * @param teamRank      固化的团队名次；不完整为 null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TeamSnapshotEntry(
            String teamId,
            int rosterVersion,
            List<String> members,
            boolean complete,
            Long teamScoreMs,
            Integer teamRank
    ) {
    }
}
