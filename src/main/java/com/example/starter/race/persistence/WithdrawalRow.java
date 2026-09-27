package com.example.starter.race.persistence;

import com.example.starter.race.domain.WithdrawalView;

/**
 * runner_withdrawal 表行记录（不可变退赛证据，一人一行）。
 *
 * @param raceId      所属赛事ID
 * @param bib         退赛选手参赛号
 * @param reason      退赛原因
 * @param officialRole 登记退赛的裁判角色标识
 * @param withdrawnAt 退赛提交的绝对UTC毫秒时间戳
 * @param createdAt   记录写入的绝对UTC毫秒时间戳
 */
public record WithdrawalRow(
        String raceId,
        String bib,
        String reason,
        String officialRole,
        long withdrawnAt,
        long createdAt
) implements WithdrawalView {
}
