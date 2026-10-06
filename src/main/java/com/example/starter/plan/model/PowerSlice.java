package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 恒定功率时间片：将全部相关占用的起止点合并后，相邻边界之间功率恒定的区间，
 * 区间左闭右开 [startUtc, endUtc)。
 *
 * @param startUtc     时间片开始（含），UTC
 * @param endUtc       时间片结束（不含），UTC
 * @param totalPowerKw 该片内累计功率，千瓦；同一列车不同占用分别累计，精确整数求和
 */
public record PowerSlice(Instant startUtc, Instant endUtc, long totalPowerKw) {
}
