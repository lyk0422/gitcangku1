package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 铁路走廊日计划主记录。
 *
 * @param id                主键
 * @param scheduleKey       计划业务键，全局唯一
 * @param opDate            运营日期（Asia/Shanghai 日历日）
 * @param version           版本号，草稿占用整体替换成功一次加一
 * @param status            计划状态
 * @param stockNo           车底标识；{code null} 表示未登记车底，不参与交路衔接
 * @param originStation     始发站（首站）；登记车底时非空
 * @param destinationStation 终到站（末站）；登记车底时非空
 * @param rearrangePending  待重排标记；{@code true} 表示该车底交路中间段取消后，本段作为后续段待人工重排
 */
public record DayPlan(long id, String scheduleKey, LocalDate opDate, int version, PlanStatus status,
                      String stockNo, String originStation, String destinationStation,
                      boolean rearrangePending) {
}
