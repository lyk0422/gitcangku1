package com.example.starter.exposure.domain;

/**
 * 公告 PO。公告以 campaignId 唯一，创建时固定每 UTC 日总额度与每访客每日上限；
 * 归因窗口可调整，每次调整窗口版本号 +1，历史版本见 campaign_window_history。
 *
 * @param campaignId                公告编号，全局唯一
 * @param dailyTotalCap             每 UTC 日总额度，单位次，取值 1～100000
 * @param perVisitorDailyCap        每访客每 UTC 日上限，单位次，取值 1～100000
 * @param attributionWindowMinutes  当前生效的 UTC 归因窗口，单位分钟，取值 1～1440
 * @param windowVersion             当前归因窗口版本号，从 1 开始单调递增
 * @param createdAtUtc              创建时刻（epoch 毫秒，UTC）
 */
public record Campaign(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        int attributionWindowMinutes,
        int windowVersion,
        long createdAtUtc
) {
}
