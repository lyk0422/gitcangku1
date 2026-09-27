package com.example.starter.exposure.domain;

/**
 * 公告 PO。公告以 campaignId 唯一，创建时固定每 UTC 日总额度与每访客每日上限；
 * 归因窗口分钟数可按版本调整，历史归因始终按预占时快照的版本裁决。
 *
 * @param campaignId                公告编号，全局唯一
 * @param dailyTotalCap             每 UTC 日总额度，单位次，取值 1～100000
 * @param perVisitorDailyCap        每访客每 UTC 日上限，单位次，取值 1～100000
 * @param attributionWindowMinutes  当前 UTC 归因窗口分钟数（要求值），取值 1～1440，区间左闭右开
 * @param windowVersion             当前窗口版本号，从 1 起，每次调整 +1
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
