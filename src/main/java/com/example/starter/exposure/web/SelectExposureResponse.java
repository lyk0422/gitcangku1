package com.example.starter.exposure.web;

import java.util.Map;

/**
 * 轮转池选择结果视图。deficits 为本次选择保存的新缺额（临时缺额，
 * 预占 CONFIRMED 后正式提交，取消/到期则丢弃），按公告号字典序排列。
 *
 * @param poolId        轮转池编号
 * @param version       选择时的池配置版本
 * @param visitorId     合成访客编号
 * @param campaignId    本次选中的公告编号
 * @param reservation   原子创建的 60 秒预占单
 * @param deficits      选择后各成员的新缺额（公告号 → 缺额，单位次）
 * @param selectedAtUtc 选择时刻，epoch 毫秒，UTC
 */
public record SelectExposureResponse(
        String poolId,
        int version,
        String visitorId,
        String campaignId,
        ReservationResponse reservation,
        Map<String, Long> deficits,
        long selectedAtUtc
) {
}
