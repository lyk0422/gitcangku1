package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.CampaignWindow;

/**
 * 归因窗口版本历史视图。
 *
 * @param campaignId    公告编号
 * @param windowVersion 窗口版本号
 * @param windowMinutes 该版本的 UTC 归因窗口，单位分钟
 * @param changedAtUtc  该版本生效时刻，epoch 毫秒，UTC
 */
public record WindowVersionResponse(
        String campaignId,
        int windowVersion,
        int windowMinutes,
        long changedAtUtc
) {
    public static WindowVersionResponse from(CampaignWindow window) {
        return new WindowVersionResponse(
                window.campaignId(),
                window.windowVersion(),
                window.windowMinutes(),
                window.changedAtUtc());
    }
}
