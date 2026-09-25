package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 铁路走廊日计划主记录。
 *
 * @param id           主键
 * @param scheduleKey  计划业务键，全局唯一
 * @param opDate       运营日期（Asia/Shanghai 日历日）
 * @param version      版本号，草稿占用整体替换/编组登记成功一次加一
 * @param status       计划状态
 * @param platformRisk 是否带 PLATFORM_RISK 站台风险标记（站台下调后超长，待整改）
 */
public record DayPlan(long id, String scheduleKey, LocalDate opDate, int version, PlanStatus status,
                      boolean platformRisk) {
}
