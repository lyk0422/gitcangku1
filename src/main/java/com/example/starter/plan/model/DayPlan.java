package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 铁路走廊日计划主记录。
 *
 * @param id           主键
 * @param scheduleKey  计划业务键，全局唯一
 * @param opDate       运营日期（Asia/Shanghai 日历日）
 * @param version      版本号，草稿占用整体替换成功一次加一
 * @param status       计划状态
 * @param overnight    是否夜间跨零点计划；true 时占用允许从运营日 22:00 延伸至次日 06:00
 * @param nightPairKey 夜间计划对业务键，null 表示普通单日计划
 */
public record DayPlan(long id, String scheduleKey, LocalDate opDate, int version, PlanStatus status,
                      boolean overnight, String nightPairKey) {
}
