package com.example.starter.exposure.web;

import java.time.LocalDate;

/**
 * 静默抑制统计视图：按公告、访客与 UTC 日返回累计抑制次数。账目行不存在时次数为 0。
 *
 * @param campaignId       公告编号
 * @param visitorId        访客编号
 * @param utcDate          查询的 UTC 日，格式 yyyy-MM-dd
 * @param suppressedCount  当日该公告该访客被抑制累计次数，单位次
 */
public record SuppressionStatsResponse(
        String campaignId,
        String visitorId,
        LocalDate utcDate,
        int suppressedCount
) {
}
