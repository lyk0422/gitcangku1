package com.example.starter.race.api;

/**
 * 让分系数写入响应。
 *
 * @param raceId  赛事ID
 * @param bib     选手参赛号
 * @param p       让分系数分子（约分后）
 * @param q       让分系数分母（约分后）
 * @param version 写入后的赛事版本号
 */
public record HandicapFactorResponse(
        String raceId,
        String bib,
        int p,
        int q,
        int version
) {
}
