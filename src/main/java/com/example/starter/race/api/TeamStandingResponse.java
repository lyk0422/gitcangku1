package com.example.starter.race.api;

/**
 * 队伍团队得分（OPEN 时为重算结果，SEALED 时为封榜固化快照）。
 *
 * @param raceId        所属赛事ID
 * @param teamId        队伍ID
 * @param rosterVersion 得分对应的名单版本
 * @param raceVersion   得分对应的赛事版本（个人成绩版本）
 * @param memberCount   锁定名单人数
 * @param rankedCount   RANKED 成员人数
 * @param totalTimeMs   团队得分（毫秒）；存在未排名成员为 null（不完整）
 */
public record TeamStandingResponse(
        String raceId,
        String teamId,
        int rosterVersion,
        int raceVersion,
        int memberCount,
        int rankedCount,
        Long totalTimeMs
) {
}
