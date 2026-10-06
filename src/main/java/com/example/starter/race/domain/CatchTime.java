package com.example.starter.race.domain;

/**
 * 最早交会时刻的精确有理数表示（起跑起毫秒），已约分且分母为正。
 *
 * @param numerator   分子
 * @param denominator 分母（正整数）
 */
public record CatchTime(long numerator, long denominator) {
}
