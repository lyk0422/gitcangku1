package com.example.starter.race.persistence;

/**
 * team_standing 表行记录（已锁定队伍按当前赛事版本重算的团队得分）。
 *
 * @param raceId        所属赛事ID
 * @param teamId        队伍ID
 * @param rosterVersion 得分对应的锁定名单版本
 * @param raceVersion   得分重算时的赛事版本（与个人成绩版本一致）
 * @param memberCount   锁定名单人数（2~8）
 * @param rankedCount   锁定名单中状态为 RANKED 的成员人数
 * @param totalTimeMs   团队得分=全部 RANKED 成员总耗时之和（毫秒）；存在未排名成员为 null（不完整）
 * @param computedAt    重算时间，Unix毫秒时间戳
 */
public record TeamStandingRow(
        String raceId,
        String teamId,
        int rosterVersion,
        int raceVersion,
        int memberCount,
        int rankedCount,
        Long totalTimeMs,
        long computedAt) {
}
