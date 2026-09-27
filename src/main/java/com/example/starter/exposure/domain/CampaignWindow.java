package com.example.starter.exposure.domain;

/**
 * 公告归因窗口版本历史 PO。每行不可变：调整窗口只插入新版本行，
 * 历史版本及其窗口分钟数永不改写，历史归因不随后续调整重算。
 *
 * @param campaignId    公告编号
 * @param windowVersion 窗口版本号，从 1 开始单调递增
 * @param windowMinutes 该版本的 UTC 归因窗口，单位分钟，取值 1～1440
 * @param changedAtUtc  该版本生效（调整发生）时刻，epoch 毫秒，UTC
 */
public record CampaignWindow(
        String campaignId,
        int windowVersion,
        int windowMinutes,
        long changedAtUtc
) {
}
