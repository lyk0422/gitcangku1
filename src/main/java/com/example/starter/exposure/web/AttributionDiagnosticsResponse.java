package com.example.starter.exposure.web;

import java.time.LocalDate;

/**
 * 公告归因诊断视图。数量字段为所查范围内的实际回执数；
 * 当前窗口版本与分钟数为查询时刻公告的生效值（历史回执按其各自快照裁决，不回算）。
 *
 * @param campaignId            公告编号
 * @param utcDate               统计的回执上报日（UTC，按 receiptAtUtc 过滤）；未指定为 null 表示全部
 * @param currentWindowVersion  查询时刻当前生效的窗口版本号
 * @param currentWindowMinutes  查询时刻当前生效的窗口分钟数
 * @param attributedCount       窗口内计入归因的回执数，单位条
 * @param lateReceiptCount      窗口外迟到回执数，单位条
 * @param totalReceipts         回执总数，单位条
 */
public record AttributionDiagnosticsResponse(
        String campaignId,
        LocalDate utcDate,
        int currentWindowVersion,
        int currentWindowMinutes,
        long attributedCount,
        long lateReceiptCount,
        long totalReceipts
) {
}
