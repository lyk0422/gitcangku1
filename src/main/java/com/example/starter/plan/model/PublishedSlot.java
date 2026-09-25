package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 已发布计划的一条生效时隙（跨计划冲突检测与查询结果共用）。
 *
 * @param planId      所属计划 id
 * @param scheduleKey 所属计划业务键
 * @param trainNo     列车编号
 * @param sectionId   区段 ID
 * @param startUtc    占用开始（含），UTC
 * @param endUtc      占用结束（不含），UTC
 * @param planLevel   所属计划发布时继承的等级
 */
public record PublishedSlot(long planId, String scheduleKey, String trainNo, String sectionId,
                            Instant startUtc, Instant endUtc, Integer planLevel) {
}
