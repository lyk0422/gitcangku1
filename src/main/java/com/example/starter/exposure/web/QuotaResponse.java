package com.example.starter.exposure.web;

import java.time.LocalDate;

/**
 * 额度视图。按 公告/访客/UTC 日查询；访客维度为空时仅返回公告当日总额度。
 * 滚动权重与每日额度分别展示：权重为访客跨全部新类型公告的 600 秒滚动窗口占用。
 *
 * @param campaignId          公告编号
 * @param visitorId           访客编号；仅查公告总额时为 null
 * @param utcDate             查询的 UTC 日，格式 yyyy-MM-dd
 * @param dailyTotalCap       公告当日总额度，单位次
 * @param usedTotal           公告当日已占用额度（RESERVED 与 CONFIRMED 合计），单位次
 * @param remainingTotal      公告当日剩余总额度，单位次
 * @param perVisitorDailyCap  该访客当日上限；仅查总额时为 null
 * @param usedVisitor         该访客当日已占用额度；仅查总额时为 null
 * @param remainingVisitor    该访客当日剩余额度；仅查总额时为 null
 * @param attentionWeight     该公告的固定注意力权重（1～5）；旧类型公告为 null
 * @param windowSeconds       滚动窗口长度，单位秒，固定 600
 * @param windowWeightCap     滚动窗口权重上限，单位权重点，固定 10
 * @param usedWeight          该访客当前窗口内已占用权重（跨全部新类型公告）；
 *                            未指定访客时为 null
 * @param remainingWeight     该访客当前窗口剩余权重；未指定访客时为 null
 * @param settledAtUtc        查询前结算过期预占所用的当前时刻，epoch 毫秒，UTC
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
        Integer attentionWeight,
        Integer windowSeconds,
        Integer windowWeightCap,
        Integer usedWeight,
        Integer remainingWeight,
        long settledAtUtc
) {
}
