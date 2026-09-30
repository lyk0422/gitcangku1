package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 站台到发占用视图（站台管理方查询）。仅含当前生效（来源计划 PUBLISHED）的登记。
 *
 * @param scheduleKey       来源计划业务键
 * @param trainNo           列车编号
 * @param platformId        停靠站台 ID
 * @param startUtc          到达占用开始时刻（含），UTC
 * @param endUtc            发车占用结束时刻（不含），UTC
 * @param capabilityVersion 登记时快照的站台能力版本
 */
public record PlatformBookingView(String scheduleKey, String trainNo, String platformId,
                                  Instant startUtc, Instant endUtc, int capabilityVersion) {
}
