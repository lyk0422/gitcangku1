package com.example.starter.race.domain;

/**
 * 团队中单个成员的计分信息。
 *
 * @param bib          参赛号
 * @param personalRank 个人名次（封榜/即时成绩中的名次）；非 RANKED 为 null
 * @param status       个人成绩状态
 * @param totalTimeMs  含有效处罚的个人总耗时（毫秒）；非 RANKED 为 null
 * @param scored       是否入选团队计分前3人
 */
public record TeamMemberScore(
        String bib,
        Integer personalRank,
        EntryStatus status,
        Long totalTimeMs,
        boolean scored
) {
}
