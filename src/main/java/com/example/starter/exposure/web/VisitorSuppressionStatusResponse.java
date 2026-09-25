package com.example.starter.exposure.web;

/**
 * 访客抑制状态视图。
 *
 * @param campaignId      公告编号
 * @param visitorId       访客编号
 * @param suppressed      当前时刻是否被抑制
 * @param reason          被抑制原因（SUPPRESSED_BY_INTERVAL）；未抑制为 null
 * @param matchedInterval 命中的抑制区间；未抑制为 null
 * @param checkedAtUtc    判定时刻（服务端当前时刻），epoch 毫秒，UTC
 */
public record VisitorSuppressionStatusResponse(
        String campaignId,
        String visitorId,
        boolean suppressed,
        String reason,
        SuppressionIntervalResponse matchedInterval,
        long checkedAtUtc
) {
}
