package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 铁路走廊日计划主记录。
 *
 * @param id            主键
 * @param scheduleKey   计划业务键，全局唯一
 * @param opDate        运营日期（Asia/Shanghai 日历日）
 * @param version       版本号，草稿占用整体替换成功一次加一
 * @param status        计划状态
 * @param stockKey      车底标识，null 表示不参与车底交路链
 * @param originStation 始发站，null 表示未登记车底交路
 * @param destStation   终到站，null 表示未登记车底交路
 * @param chainState    交路链状态（NORMAL / PENDING_REPLAN）
 */
public record DayPlan(long id, String scheduleKey, LocalDate opDate, int version, PlanStatus status,
                      String stockKey, String originStation, String destStation,
                      ChainState chainState) {
}
