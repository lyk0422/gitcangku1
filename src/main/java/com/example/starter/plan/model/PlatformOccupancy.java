package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 站台时间占用：已发布计划在某站台的停靠时段（用于同站台重叠校验与占用查询）。
 *
 * @param scheduleKey 计划业务键
 * @param platformCode 站台代码
 * @param startUtc    占用开始时刻（含），UTC
 * @param endUtc      占用结束时刻（不含），UTC
 */
public record PlatformOccupancy(String scheduleKey, String platformCode,
                                Instant startUtc, Instant endUtc) {
}
