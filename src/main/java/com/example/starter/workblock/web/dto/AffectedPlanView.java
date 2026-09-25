package com.example.starter.workblock.web.dto;

import java.time.Instant;

/**
 * 受生效施工窗口影响的已发布计划占用视图。
 *
 * @param scheduleKey 计划业务键
 * @param trainNo     列车编号
 * @param sectionId   冲突区段
 * @param startUtc    计划占用开始时刻（含），UTC
 * @param endUtc      计划占用结束时刻（不含），UTC
 * @param workKey     相交的施工单业务键
 * @param windowStart 施工窗口开始时刻（含），UTC
 * @param windowEnd   施工窗口结束时刻（不含），UTC
 */
public record AffectedPlanView(String scheduleKey, String trainNo, String sectionId,
                               Instant startUtc, Instant endUtc,
                               String workKey, Instant windowStart, Instant windowEnd) {
}
