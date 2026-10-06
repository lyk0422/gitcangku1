package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 已发布计划的一条生效时隙（跨计划冲突检测、功率预算与查询结果共用）。
 *
 * @param scheduleKey 所属计划业务键
 * @param trainNo     列车编号
 * @param sectionId   区段 ID
 * @param startUtc    占用开始（含），UTC
 * @param endUtc      占用结束（不含），UTC
 * @param powerKw     声明取电功率，非负整数千瓦
 */
public record PublishedSlot(String scheduleKey, String trainNo, String sectionId,
                            Instant startUtc, Instant endUtc, int powerKw) {
}
