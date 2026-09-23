package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 三层额度视图。按 公告/访客/展示位/UTC 日查询；
 * 访客与展示位维度为空时对应字段为 null。旧查询（不传 placementCode）响应字段保持不变，
 * 新增字段以追加方式返回（无该维度时为 null，{@code reservations} 为空数组或全部明细）。
 *
 * @param campaignId          公告编号
 * @param visitorId           访客编号；仅查公告总额时为 null
 * @param utcDate             查询的 UTC 日，格式 yyyy-MM-dd
 * @param dailyTotalCap       公告当日总额度，单位次
 * @param usedTotal           公告当日已占用额度（RESERVED 与 CONFIRMED 合计），单位次
 * @param remainingTotal      公告当日剩余总额度，单位次
 * @param perVisitorDailyCap  该访客当日上限；仅查总额时为 null
 * @param usedVisitor         该访客当日跨全部展示位共享已占用额度；仅查总额时为 null
 * @param remainingVisitor    该访客当日剩余共享额度；仅查总额时为 null
 * @param settledAtUtc        查询前结算过期预占所用的当前时刻，epoch 毫秒，UTC
 * @param configVersion       公告当前展示位配置版本号
 * @param placementCode       查询的展示位编号；未指定时为 null
 * @param placementDailyCap   该展示位当日额度；未指定展示位时为 null
 * @param usedPlacement       该展示位当日已占用额度；未指定展示位时为 null
 * @param remainingPlacement  该展示位当日剩余额度；未指定展示位时为 null
 * @param reservations        当日仍占用额度（RESERVED/CONFIRMED）的预占展示位明细，
 *                            按 visitorId/placementCode 过滤后返回
 */
public record QuotaResponse(
        String campaignId,
        String visitorId,
        LocalDate utcDate,
        int dailyTotalCap,
        int usedTotal,
        int remainingTotal,
        Integer perVisitorDailyCap,
        Integer usedVisitor,
        Integer remainingVisitor,
        long settledAtUtc,
        int configVersion,
        String placementCode,
        Integer placementDailyCap,
        Integer usedPlacement,
        Integer remainingPlacement,
        List<ReservationPlacementDetail> reservations
) {
}
