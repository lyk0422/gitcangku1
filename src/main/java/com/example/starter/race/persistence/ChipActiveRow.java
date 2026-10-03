package com.example.starter.race.persistence;

/**
 * chip_active 表行记录：芯片当前生效绑定（全局每芯片至多一行）。
 *
 * @param chipCode 计时芯片代码
 * @param raceId   当前绑定选手所属赛事ID
 * @param bib      当前绑定选手参赛号
 * @param boundAt  本次绑定生效时刻，Unix毫秒时间戳
 */
public record ChipActiveRow(
        String chipCode,
        String raceId,
        String bib,
        long boundAt
) {
}