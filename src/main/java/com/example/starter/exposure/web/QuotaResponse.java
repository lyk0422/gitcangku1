package com.example.starter.exposure.web;

import java.time.LocalDate;

/**
 * 额度视图。按 公告/访客/UTC 日查询；访客维度为空时仅返回公告当日总额度。
 * 活动小时字段追加在历史字段之后，公告未归属活动或无当日配置时为 null。
 *
 * @param campaignId            公告编号
 * @param visitorId             访客编号；仅查公告总额时为 null
 * @param utcDate               查询的 UTC 日，格式 yyyy-MM-dd
 * @param dailyTotalCap         公告当日总额度，单位次
 * @param usedTotal             公告当日已占用额度（RESERVED 与 CONFIRMED 合计），单位次
 * @param remainingTotal        公告当日剩余总额度，单位次
 * @param perVisitorDailyCap    该访客当日上限；仅查总额时为 null
 * @param usedVisitor           该访客当日已占用额度；仅查总额时为 null
 * @param remainingVisitor      该访客当日剩余额度；仅查总额时为 null
 * @param settledAtUtc          查询前结算过期预占所用的当前时刻，epoch 毫秒，UTC
 * @param activityId            公告当前归属活动编号；不参与匀速投放时为 null
 * @param utcHour               查询时刻对应的 UTC 小时（0～23）；无活动归属为 null
 * @param activityHourCap       当前小时活动预算，单位次；当日未配置为 null
 * @param usedActivityHour      当前小时活动已占用名额，单位次；当日未配置为 null
 * @param remainingActivityHour 当前小时活动剩余名额，单位次；当日未配置为 null
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
        String activityId,
        Integer utcHour,
        Integer activityHourCap,
        Integer usedActivityHour,
        Integer remainingActivityHour
) {
}
