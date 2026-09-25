package com.example.starter.exposure.web;

/**
 * 冷却状态视图。
 *
 * @param campaignId          公告编号
 * @param visitorId           访客编号
 * @param cooldownMinutes     公告当前冷却分钟数，0 表示不限制
 * @param lastConfirmedAtUtc  该访客对该公告最近一次 CONFIRMED 的确认时刻，epoch 毫秒，UTC；
 *                            从未确认为 null
 * @param cooldownUntilUtc    冷却结束时刻（lastConfirmedAtUtc + cooldownMinutes），epoch 毫秒，UTC；
 *                            无冷却限制或从未确认为 null
 * @param inCooldown          当前是否处于冷却期（申请将被 429 拦截）
 * @param checkedAtUtc        本次判定所用的服务端当前时刻，epoch 毫秒，UTC
 */
public record CooldownStatusResponse(
        String campaignId,
        String visitorId,
        int cooldownMinutes,
        Long lastConfirmedAtUtc,
        Long cooldownUntilUtc,
        boolean inCooldown,
        long checkedAtUtc
) {
}
