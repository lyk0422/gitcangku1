package com.example.starter.plan.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 已发布计划的一条生效时隙（跨计划冲突检测与查询结果共用）。
 *
 * @param scheduleKey 所属计划业务键
 * @param opDate      所属计划运营日期（Asia/Shanghai 日历日）
 * @param trainNo     列车编号
 * @param sectionId   区段 ID
 * @param startUtc    占用开始（含），UTC
 * @param endUtc      占用结束（不含），UTC
 */
public record PublishedSlot(String scheduleKey, LocalDate opDate, String trainNo, String sectionId,
                            Instant startUtc, Instant endUtc) {
}
