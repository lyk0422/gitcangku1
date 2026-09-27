package com.example.starter.race.persistence;

/**
 * runner_withdrawal 表行记录：退赛不可变事件，仅插入永不更新。
 *
 * @param raceId    所属赛事ID
 * @param bib       退赛选手参赛号
 * @param reason    退赛原因；未填写为 null
 * @param createdAt 退赛提交时刻，Unix毫秒时间戳（UTC）
 */
public record RunnerWithdrawalRow(
        String raceId,
        String bib,
        String reason,
        long createdAt
) {
}
