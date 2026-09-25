package com.example.starter.exposure.web;

/**
 * 访客被抑制原因视图：命中的有效抑制区间及说明。
 *
 * @param intervalId 命中区间编号
 * @param visitorId  被抑制访客编号
 * @param startAtUtc 区间生效开始时刻，epoch 毫秒，UTC，左闭
 * @param endAtUtc   区间当前生效结束时刻，epoch 毫秒，UTC，右开
 * @param reason     人类可读的抑制原因说明
 */
public record SuppressionReasonResponse(
        String intervalId,
        String visitorId,
        long startAtUtc,
        long endAtUtc,
        String reason
) {
}
