package com.example.starter.exposure.domain;

/**
 * 公告 PO。公告以 campaignId 唯一，创建时固定每 UTC 日总额度与每访客每日上限。
 *
 * @param campaignId         公告编号，全局唯一
 * @param dailyTotalCap      每 UTC 日总额度，单位次，取值 1～100000
 * @param perVisitorDailyCap 每访客每 UTC 日上限，单位次，取值 1～100000
 * @param createdAtUtc       创建时刻（epoch 毫秒，UTC）
 * @param category           活动类别；访客同意按 访客+类别 裁决；null 表示历史公告不校验同意
 * @param version            活动版本号，初始为 1，类别每修改一次 +1；进入预占请求指纹
 * @param silentStartMinute  静默时段起点（UTC 日内分钟，0～1439）；null 表示无静默时段
 * @param silentEndMinute    静默时段终点（UTC 日内分钟，1～1440，左闭右开）；允许不大于起点以表示跨午夜
 */
public record Campaign(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        long createdAtUtc,
        String category,
        int version,
        Integer silentStartMinute,
        Integer silentEndMinute
) {
}
