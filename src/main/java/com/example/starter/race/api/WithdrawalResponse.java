package com.example.starter.race.api;

/**
 * 退赛响应。
 *
 * @param bib         参赛号
 * @param withdrawnAt 退赛时间，Unix毫秒时间戳
 * @param version     退赛后的赛事版本号
 */
public record WithdrawalResponse(String bib, long withdrawnAt, int version) {
}