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
 * @param driverId    司机乘务员 id，发布/改签时指定，{@code null} 表示未指定乘务
 * @param conductorId 车长乘务员 id，发布/改签时指定，{@code null} 表示未指定乘务
 * @param riskBlocked 乘务风险门禁：true 表示资质被提前终止，禁止普通改签与同车底新增段发布
 */
public record DayPlan(long id, String scheduleKey, LocalDate opDate, int version, PlanStatus status,
                      String driverId, String conductorId, boolean riskBlocked) {
}
