package com.example.starter.race.persistence;

/**
 * runner_chip 表行记录：选手芯片绑定区间（左闭右开）。
 *
 * @param id            自增主键
 * @param raceId        所属赛事ID
 * @param bib           选手参赛号
 * @param chipCode      计时芯片代码
 * @param effectiveFrom 生效时刻（Unix毫秒时间戳，UTC，含）
 * @param effectiveTo   失效时刻（Unix毫秒时间戳，UTC，不含）；null 表示当前生效中
 * @param createdAt     绑定登记时间，Unix毫秒时间戳
 */
public record RunnerChipRow(
        long id,
        String raceId,
        String bib,
        String chipCode,
        long effectiveFrom,
        Long effectiveTo,
        long createdAt
) {
}