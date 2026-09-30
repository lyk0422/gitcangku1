package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 乘务员人员冲突明细：与另一张已发布计划在时间窗上左闭右开重叠。
 *
 * @param scheduleKey 冲突计划业务键
 * @param startUtc    冲突占用窗口开始时刻（含），UTC
 * @param endUtc      冲突占用窗口结束时刻（不含），UTC
 */
public record CrewConflictView(String scheduleKey, Instant startUtc, Instant endUtc) {
}
