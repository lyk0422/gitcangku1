package com.example.starter.race.api;

/**
 * 退赛不可变记录响应。
 *
 * @param raceId      所属赛事ID
 * @param bib         退赛选手参赛号
 * @param reason      退赛原因
 * @param officialRole 登记退赛的裁判角色标识
 * @param withdrawnAt 退赛生效的绝对UTC毫秒时间戳
 * @param createdAt   记录写入的绝对UTC毫秒时间戳
 */
public record WithdrawalResponse(
        String raceId,
        String bib,
        String reason,
        String officialRole,
        long withdrawnAt,
        long createdAt
) {
}
