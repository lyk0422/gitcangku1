package com.example.starter.exposure.domain;

/**
 * 访客对公告的冷却状态 PO。行仅在首次确认时创建，无行表示从未确认、无冷却限制。
 *
 * @param campaignId         公告编号
 * @param visitorId          合成访客编号
 * @param lastConfirmedAtUtc 该访客对该公告最近一次 CONFIRMED 的确认时刻（epoch 毫秒，UTC）；
 *                           取消/过期不更新
 */
public record VisitorCooldown(
        String campaignId,
        String visitorId,
        long lastConfirmedAtUtc
) {
}
