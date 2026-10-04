package com.example.starter.plan.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 已发布计划在某进路上的生效占用，用于新增冲突对时校验是否撞上已发布占用。
 *
 * @param opDate      运营日期（Asia/Shanghai 日历日）
 * @param scheduleKey 所属计划业务键
 * @param routeId     进路编号（非空）
 * @param startUtc    占用开始（含），UTC
 * @param endUtc      占用结束（不含），UTC
 */
public record PublishedRouteSlot(LocalDate opDate, String scheduleKey, String routeId,
                                 Instant startUtc, Instant endUtc) {
}