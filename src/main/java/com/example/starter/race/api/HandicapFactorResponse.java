package com.example.starter.race.api;

/**
 * 让分系数写入响应。
 *
 * @param raceId  赛事ID
 * @param bib     参赛号
 * @param p       约分后的让分系数分子
 * @param q       约分后的让分系数分母
 * @param version 写入后的赛事版本号
 */
public record HandicapFactorResponse(String raceId, String bib, int p, int q, int version) {
}
