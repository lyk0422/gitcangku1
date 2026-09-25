package com.example.starter.exposure.web;

import java.util.List;

/**
 * 公告渠道归属统计视图。按预占创建时固化的渠道分组（不随公告迁移变化）；
 * {@code null} 渠道分组表示申请时未固化渠道的预占。
 *
 * @param campaignId 公告编号
 * @param entries    各渠道分组的统计明细
 */
public record CampaignAttributionResponse(
        String campaignId,
        List<Entry> entries
) {
    /**
     * 单个渠道分组的预占状态计数，单位条。
     *
     * @param channelKey 固化渠道编号；null 表示申请时未固化渠道
     * @param reserved   RESERVED 状态预占数
     * @param confirmed  CONFIRMED 状态预占数
     * @param cancelled  CANCELLED 状态预占数
     * @param expired    EXPIRED 状态预占数
     */
    public record Entry(
            String channelKey,
            int reserved,
            int confirmed,
            int cancelled,
            int expired
    ) {
    }
}
