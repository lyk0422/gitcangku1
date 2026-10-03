package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 已发布计划上的一名乘务员生效占用窗口（人员冲突检测与查询结果共用）。
 *
 * @param scheduleKey 所属计划业务键
 * @param memberCode  乘务员业务键
 * @param startUtc    占用开始（含），UTC
 * @param endUtc      占用结束（不含），UTC
 */
public record OccupiedCrewSlot(String scheduleKey, String memberCode,
                               Instant startUtc, Instant endUtc) {
}