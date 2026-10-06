package com.example.starter.race.persistence;

import com.example.starter.race.domain.ResultCalculator;

/**
 * handicap_factor 表行记录：HANDICAP 赛事中某选手的让分系数（约分后保存）。
 *
 * @param raceId    所属赛事ID
 * @param bib       选手参赛号
 * @param p         让分系数分子（约分后，1~1000）
 * @param q         让分系数分母（约分后，1~1000）
 * @param createdAt 系数首次登记时间，Unix毫秒时间戳
 * @param updatedAt 系数最近修改时间，Unix毫秒时间戳
 */
public record HandicapFactorRow(
        String raceId,
        String bib,
        int p,
        int q,
        long createdAt,
        long updatedAt
) implements ResultCalculator.FactorView {
}
