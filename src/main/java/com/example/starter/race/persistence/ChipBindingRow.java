package com.example.starter.race.persistence;

/**
 * chip_binding 表行记录：一枚计时芯片当前唯一绑定的未完赛参赛者。
 *
 * <p>芯片代码为主键，保证同一芯片不会同时归属两名选手；
 * {@code (race_id, bib)} 唯一约束保证一名选手当前至多持有一枚芯片。
 *
 * @param chipCode 芯片代码，全局唯一
 * @param raceId   当前绑定选手所属赛事ID
 * @param bib      当前绑定选手参赛号
 * @param boundAt  本次绑定建立时间，Unix毫秒时间戳（UTC）
 */
public record ChipBindingRow(
        String chipCode,
        String raceId,
        String bib,
        long boundAt
) {
}
