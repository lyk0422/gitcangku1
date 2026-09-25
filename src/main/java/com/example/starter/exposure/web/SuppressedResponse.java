package com.example.starter.exposure.web;

/**
 * 曝光申请被抑制名单命中的响应。不创建预占、不扣频次或预算。
 *
 * @param campaignId    公告编号
 * @param visitorId     访客编号
 * @param placementId   展示位编号
 * @param status        固定为 SUPPRESSED
 * @param reason        被抑制原因，固定为 SUPPRESSED_BY_INTERVAL
 * @param intervalId    命中的抑制区间编号
 * @param validFromUtc  命中区间生效起始时刻（含），epoch 毫秒，UTC
 * @param validUntilUtc 命中区间生效结束时刻（不含），epoch 毫秒，UTC
 * @param decidedAtUtc  裁决时刻（服务端当前时刻），epoch 毫秒，UTC
 */
public record SuppressedResponse(
        String campaignId,
        String visitorId,
        String placementId,
        String status,
        String reason,
        String intervalId,
        long validFromUtc,
        long validUntilUtc,
        long decidedAtUtc
) implements ApplyResult {

    public static final String STATUS = "SUPPRESSED";
    public static final String REASON = "SUPPRESSED_BY_INTERVAL";
}
