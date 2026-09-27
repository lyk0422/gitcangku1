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
 * @param riskStatus  接续风险当前标记：NONE / CONNECTION_AT_RISK；当前态可迁移，历史事件不改写
 */
public record DayPlan(long id, String scheduleKey, LocalDate opDate, int version, PlanStatus status,
                      String riskStatus) {

    /** 无接续风险标记。 */
    public static final String RISK_NONE = "NONE";
    /** 接续保障不足，等待保障改签。 */
    public static final String RISK_CONNECTION_AT_RISK = "CONNECTION_AT_RISK";
}
