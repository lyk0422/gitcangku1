package com.example.starter.race.domain;

import java.util.List;

/**
 * 一支已锁定队伍的团队成绩条目。
 *
 * @param teamId        队伍ID
 * @param rosterVersion 计算所依据的锁定名单版本
 * @param members       锁定名单成员参赛号（按字典序）
 * @param complete      全部成员均为 RANKED 时为 true，团队参与排名
 * @param teamScoreMs   团队得分=全部成员总耗时之和（毫秒）；不完整队伍为 null
 * @param teamRank      团队名次，并列同名次并跳号（1、1、3）；不完整队伍为 null
 */
public record TeamStanding(
        String teamId,
        int rosterVersion,
        List<String> members,
        boolean complete,
        Long teamScoreMs,
        Integer teamRank
) {
}
