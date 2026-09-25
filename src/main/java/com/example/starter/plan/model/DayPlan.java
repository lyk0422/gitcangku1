package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 铁路走廊日计划主记录。
 *
 * @param id            主键
 * @param scheduleKey   计划业务键，全局唯一
 * @param opDate        运营日期（Asia/Shanghai 日历日）
 * @param version       版本号，草稿占用整体替换或编组变更成功一次加一
 * @param status        计划状态
 * @param consistLength 编组长度（辆，与站台有效长度同单位），未登记为 null
 */
public record DayPlan(long id, String scheduleKey, LocalDate opDate, int version, PlanStatus status,
                      Integer consistLength) {
}
