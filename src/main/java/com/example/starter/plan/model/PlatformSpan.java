package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 已发布计划在某站台上的占用窗口（计划占用区段的最开始至最末结束，区间左闭右开）。
 *
 * @param scheduleKey   计划业务键
 * @param platformCode  站台代码
 * @param consistLength 编组长度（辆），未登记为 null
 * @param startUtc      占用开始时刻（含），UTC
 * @param endUtc        占用结束时刻（不含），UTC
 */
public record PlatformSpan(String scheduleKey, String platformCode, Integer consistLength,
                           Instant startUtc, Instant endUtc) {
}
