package com.example.starter.exposure.web;

import java.time.LocalDate;

/**
 * 三层额度视图。按 公告/访客/展示位/UTC 日查询。
 *
 * <p>旧查询（不带 placementCode）响应保持兼容：placement* 字段为 null，
 * visitorId 为空时访客与展示位字段均为 null。</p>
 *
 * @param campaignId          公告编号
 * @param visitorId           访客编号；仅查公告总额时为 null
 * @param placementCode       展示位编号；未指定展示位维度时为 null
 * @param utcDate             查询的 UTC 日，格式 yyyy-MM-dd
 * @param dailyTotalCap       公告当日总额度，单位次
 * @param usedTotal           公告当日已占用额度（RESERVED 与 CONFIRMED 合计），单位次
 * @param remainingTotal      公告当日剩余总额度，单位次
 * @param perVisitorDailyCap  该访客当日跨全部展示位共享上限；仅查总额时为 null
 * @param usedVisitor         该访客当日跨全部展示位已占用额度；仅查总额时为 null
 * @param remainingVisitor    该访客当日剩余共享额度；仅查总额时为 null
 * @param placementDailyCap   该展示位当日额度；未指定展示位时为 null
 * @param usedPlacement       该展示位当日已占用额度；未指定展示位时为 null
 * @param remainingPlacement  该展示位当日剩余额度；未指定展示位时为 null
 * @param settledAtUtc        查询前结算过期预占所用的当前时刻，epoch 毫秒，UTC
 */
public record QuotaResponse(
        String campaignId,
        String visitorId,
        String placementCode,
        LocalDate utcDate,
        int dailyTotalCap,
        int usedTotal,
        int remainingTotal,
        Integer perVisitorDailyCap,
        Integer usedVisitor,
        Integer remainingVisitor,
        Integer placementDailyCap,
        Integer usedPlacement,
        Integer remainingPlacement,
        long settledAtUtc
) {
}
