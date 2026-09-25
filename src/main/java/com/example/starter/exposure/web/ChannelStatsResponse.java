package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 渠道按公告归属统计视图：某 UTC 日渠道下各公告的预占状态分布。
 *
 * @param channelKey 渠道编号
 * @param utcDate    统计的 UTC 日，格式 yyyy-MM-dd
 * @param items      各公告统计项，按公告编号升序
 */
public record ChannelStatsResponse(
        String channelKey,
        LocalDate utcDate,
        List<Item> items
) {
    /**
     * 单个公告在渠道当日的预占状态分布。
     *
     * @param campaignId 公告编号
     * @param reserved   RESERVED 数，单位次
     * @param confirmed  CONFIRMED 数，单位次
     * @param cancelled  CANCELLED 数，单位次
     * @param expired    EXPIRED 数，单位次
     */
    public record Item(String campaignId, int reserved, int confirmed,
                       int cancelled, int expired) {
    }
}
