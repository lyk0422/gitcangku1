package com.example.starter.race.domain;

/**
 * 名单版本锁定时固化的单个成员成绩归属。
 *
 * <p>所有字段均为锁定时刻的个人成绩快照：成员后续被加时、取消资格、修订计时或退赛，
 * 都不会改写已锁定版本中的这些值。
 *
 * @param bib          参赛号
 * @param rank         锁定时固化的全场名次（从1开始，并列同名次并跳号）；非 RANKED 为 null
 * @param status       锁定时固化的个人成绩状态
 * @param finishTimeMs 锁定时固化的原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs    锁定时固化的生效加时合计毫秒数，无加时为 0
 * @param totalTimeMs  锁定时固化的总耗时（毫秒）；非 RANKED 为 null
 * @param memberPoints 该成员贡献给团队的名次积分（按固化名次计算）；非 RANKED 为 0
 */
public record RosterMemberSnapshot(
        String bib,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        int memberPoints
) {
}
