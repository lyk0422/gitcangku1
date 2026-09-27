package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 铁路走廊日计划主记录。
 *
 * @param id             主键
 * @param scheduleKey    计划业务键，全局唯一
 * @param opDate         运营日期（Asia/Shanghai 日历日）
 * @param version        版本号，草稿占用整体替换或保障改签成功一次加一
 * @param status         计划状态
 * @param connectionRisk 接续风险标记：null 无风险 / CONNECTION_AT_RISK 接续保障不足；
 *                       为当前状态标记，历史风险记录见 rail_connection_risk，不改写
 */
public record DayPlan(long id, String scheduleKey, LocalDate opDate, int version, PlanStatus status,
                      String connectionRisk) {

    /** 接续保障不足的风险标记值。 */
    public static final String RISK_CONNECTION_AT_RISK = "CONNECTION_AT_RISK";
}
