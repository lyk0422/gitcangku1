package com.example.starter.race.api;

/**
 * 退赛登记视图。
 *
 * @param bib              退赛成员参赛号
 * @param teamId           成员所属团队ID
 * @param effectiveSession 退赛生效场次号（从1开始的正整数），该场次及之后的未封榜计分排除该成员
 * @param reason           退赛原因
 * @param withdrawnAt      登记时间，Unix毫秒时间戳
 */
public record WithdrawalResponse(
        String bib,
        String teamId,
        int effectiveSession,
        String reason,
        long withdrawnAt
) {
}
