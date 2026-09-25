package com.example.starter.exposure.domain;

/**
 * 访客对公告的最近一次 CONFIRMED 确认时刻 PO。
 * 仅确认成功时 upsert；取消/过期不更新；供申请阶段冷却判定与冷却状态查询使用。
 *
 * @param campaignId           所属公告编号
 * @param visitorId            合成访客编号
 * @param lastConfirmedAtUtc   最近一次确认时刻，epoch 毫秒，UTC；该访客从未确认过则无行
 */
public record VisitorLastConfirmation(
        String campaignId,
        String visitorId,
        long lastConfirmedAtUtc
) {
}
