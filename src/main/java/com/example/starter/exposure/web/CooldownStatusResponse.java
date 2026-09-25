package com.example.starter.exposure.web;

/**
 * 冷却状态视图：某访客对某公告当前的冷却判定依据。
 *
 * @param campaignId           公告编号
 * @param visitorId            合成访客编号
 * @param cooldownMinutes      公告当前配置的最短冷却分钟数，0 表示不限制
 * @param lastConfirmedAtUtc   该访客对该公告最近一次 CONFIRMED 确认时刻，epoch 毫秒，UTC；
 *                             从未确认过为 null
 * @param cooldownUntilUtc     冷却结束的 UTC 时刻（lastConfirmedAtUtc + cooldownMinutes），
 *                             epoch 毫秒；无冷却限制或从未确认为 null
 * @param cooling              查询时刻是否仍处于冷却期内（true 表示此时申请会被 429 拦截）
 * @param checkedAtUtc         本次判定所用的服务端当前时刻，epoch 毫秒，UTC
 */
public record CooldownStatusResponse(
        String campaignId,
        String visitorId,
        int cooldownMinutes,
        Long lastConfirmedAtUtc,
        Long cooldownUntilUtc,
        boolean cooling,
        long checkedAtUtc
) {
}
