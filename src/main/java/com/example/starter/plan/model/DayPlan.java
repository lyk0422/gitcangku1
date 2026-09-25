package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 铁路走廊日计划主记录。
 *
 * @param id          主键
 * @param scheduleKey 计划业务键，全局唯一
 * @param opDate      运营日期（Asia/Shanghai 日历日）
 * @param version     版本号，草稿占用整体替换成功一次加一
 * @param status      计划状态
 * @param planLevel   发布时继承的计划等级（全部占用区段最高登记等级 1～5），草稿为 null
 */
public record DayPlan(long id, String scheduleKey, LocalDate opDate, int version, PlanStatus status,
                      Integer planLevel) {
}
