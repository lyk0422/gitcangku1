package com.example.starter.race.persistence;

/**
 * roster_withdrawal 表行记录：成员退赛登记，只追加不删除、不改写锁定名单。
 *
 * <p>同一成员在同一赛事内至多一条退赛记录（唯一键 race_id+bib）；
 * 记录具体生效场次与原因，封榜后也允许登记，但已封榜成绩不因此重算。
 *
 * @param raceId           所属赛事ID
 * @param bib              退赛成员参赛号
 * @param teamId           成员所属团队ID（退赛不变更归属）
 * @param effectiveSession 退赛生效场次号（从1开始的正整数），该场次及之后的未封榜计分排除该成员
 * @param reason           退赛原因
 * @param withdrawnAt      登记时间，Unix毫秒时间戳
 */
public record WithdrawalRow(
        String raceId,
        String bib,
        String teamId,
        int effectiveSession,
        String reason,
        long withdrawnAt
) implements com.example.starter.race.domain.TeamScoreCalculator.WithdrawalView {
}
