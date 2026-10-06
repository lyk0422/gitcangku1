package com.example.starter.domain;

/**
 * 航段位置刻度上的合成覆盖闭区间，刻度取值 [0,10000]。
 *
 * @param from 起始刻度（含）
 * @param to   结束刻度（含），不小于 from
 */
public record CoverageInterval(int from, int to) {
}
