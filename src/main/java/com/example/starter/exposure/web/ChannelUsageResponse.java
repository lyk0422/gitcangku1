package com.example.starter.exposure.web;

import java.time.LocalDate;

/**
 * 渠道某 UTC 日用量视图。
 *
 * @param channelKey    渠道编号
 * @param utcDate       查询的 UTC 日，格式 yyyy-MM-dd
 * @param dailyCap      渠道当日总确认额度，单位次
 * @param usedTotal     当日已占用名额（RESERVED 与 CONFIRMED 合计），单位次
 * @param confirmed     当日已确认名额，单位次
 * @param remaining     当日剩余名额，单位次
 * @param settledAtUtc  查询前结算过期预占所用的当前时刻，epoch 毫秒，UTC
 */
public record ChannelUsageResponse(
        String channelKey,
        LocalDate utcDate,
        int dailyCap,
        int usedTotal,
        int confirmed,
        int remaining,
        long settledAtUtc
) {
}
