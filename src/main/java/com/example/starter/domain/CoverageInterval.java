package com.example.starter.domain;

/**
 * 位置刻度 0 至 10000 上的覆盖闭区间（合成通信覆盖证明，不接真实基站）。
 *
 * @param start 起始刻度（含），范围 [0,10000]
 * @param end   结束刻度（含），范围 [0,10000]，要求 start &lt;= end
 */
public record CoverageInterval(int start, int end) {
}
