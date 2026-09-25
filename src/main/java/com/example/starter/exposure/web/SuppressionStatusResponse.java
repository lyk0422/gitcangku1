package com.example.starter.exposure.web;

/**
 * 访客抑制状态查询视图。
 *
 * @param campaignId 公告编号
 * @param visitorId  访客编号
 * @param atUtc      判定时刻，epoch 毫秒，UTC；缺省取服务端当前时刻
 * @param suppressed 当前是否被抑制：true 表示该时刻命中某 ACTIVE 半开区间
 * @param reason     被抑制原因；未抑制时为 null
 */
public record SuppressionStatusResponse(
        String campaignId,
        String visitorId,
        long atUtc,
        boolean suppressed,
        SuppressionReasonResponse reason
) {
}
