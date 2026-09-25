package com.example.starter.race.api;

import com.example.starter.race.domain.RaceStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 团队成绩榜响应：OPEN 赛事按锁定名单与当前赛事版本实时重算；SEALED 返回封榜固化内容。
 *
 * @param raceId   所属赛事ID
 * @param version  团队得分所依据的个人成绩版本（即赛事版本）
 * @param status   OPEN / SEALED
 * @param sealedAt 封榜时间，Unix毫秒时间戳；未封榜为 null
 * @param teams    团队成绩条目（完整队伍按名次在前，不完整队伍按队伍ID在后）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamStandingsResponse(
        String raceId,
        int version,
        RaceStatus status,
        Long sealedAt,
        List<TeamStandingEntry> teams
) {

    /**
     * 单支队伍的团队成绩。
     *
     * @param teamId        队伍ID
     * @param rosterVersion 计算所依据的锁定名单版本
     * @param members       锁定名单成员（按参赛号字典序）
     * @param complete      全部成员均为 RANKED
     * @param teamScoreMs   团队得分=全部成员总耗时之和（毫秒）；不完整为 null
     * @param teamRank      团队名次；不完整为 null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TeamStandingEntry(
            String teamId,
            int rosterVersion,
            List<String> members,
            boolean complete,
            Long teamScoreMs,
            Integer teamRank
    ) {
    }
}
