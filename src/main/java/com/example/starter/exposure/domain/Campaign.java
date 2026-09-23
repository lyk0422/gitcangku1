package com.example.starter.exposure.domain;

/**
 * 公告 PO。公告以 campaignId 唯一，创建时固定每 UTC 日总额度与每访客每日上限。
 *
 * @param campaignId          公告编号，全局唯一
 * @param dailyTotalCap       每 UTC 日总额度，单位次，取值 1～100000
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次，取值 1～100000
 * @param createdAtUtc        创建时刻（epoch 毫秒，UTC）
 * @param currentVersion      当前公告版本，创建时为 1；撤回时调用方须提交该版本做乐观校验
 * @param withdrawnAtUtc      撤回时刻（epoch 毫秒，UTC）；null 表示未撤回，非空时原子禁止新预占
 */
public record Campaign(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        long createdAtUtc,
        int currentVersion,
        Long withdrawnAtUtc
) {
}
