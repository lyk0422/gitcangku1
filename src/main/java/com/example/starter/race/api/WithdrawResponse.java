package com.example.starter.race.api;

/**
 * 选手退赛结果。
 *
 * @param bib         退赛选手参赛号
 * @param withdrawnAt 退赛生效时刻，Unix毫秒时间戳
 */
public record WithdrawResponse(String bib, long withdrawnAt) {
}
