package com.example.starter.exposure.web;

import java.time.LocalDate;

/**
 * 渠道日用量视图。查询前先结算该渠道当日已到期预占。
 *
 * @param channelKey    渠道编号
 * @param utcDate       查询的 UTC 日，格式 yyyy-MM-dd
 * @param dailyTotalCap 渠道当日总确认额度，单位次
 * @param used          渠道当日已占用名额（RESERVED 与 CONFIRMED 合计），单位次
 * @param remaining     渠道当日剩余名额，单位次
 * @param settledAtUtc  结算过期预占所用的当前时刻，epoch 毫秒，UTC
 */
public record ChannelUsageResponse(
        String channelKey,
        LocalDate utcDate,
        int dailyTotalCap,
        int used,
        int remaining,
        long settledAtUtc
) {
}
