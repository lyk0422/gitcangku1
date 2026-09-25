package com.example.starter.exposure.domain;

/**
 * 公告 PO。公告以 campaignId 唯一，创建时固定每 UTC 日总额度与每访客每日上限。
 *
 * @param campaignId         公告编号，全局唯一
 * @param dailyTotalCap      每 UTC 日总额度，单位次，取值 1～100000
 * @param perVisitorDailyCap 每访客每 UTC 日上限，单位次，取值 1～100000
 * @param category           活动类别；同意按 (访客, 类别) 裁决，类别修改后旧同意不迁移
 * @param version            活动版本，初始 1，每次修改类别 +1；进入预占 requestKey 指纹
 * @param silenceStartSec    静默窗口起点（UTC 日第几秒，含），与终点相等表示不启用；起点大于终点表示跨午夜
 * @param silenceEndSec      静默窗口终点（UTC 日第几秒，不含）
 * @param minIntervalMillis  同访客两次有效曝光的最小间隔（冷却频控，毫秒）；0 表示不启用
 * @param createdAtUtc       创建时刻（epoch 毫秒，UTC）
 */
public record Campaign(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        String category,
        int version,
        int silenceStartSec,
        int silenceEndSec,
        long minIntervalMillis,
        long createdAtUtc
) {
}
