package com.example.starter.exposure.web;

import java.util.List;

/**
 * 访客抑制名单状态视图。
 *
 * @param visitorId       访客编号
 * @param active          当前是否生效；true 时新曝光预占一律 409
 * @param suppressedAtUtc 最近一次加入名单生效时刻，epoch 毫秒，UTC；从未加入为 null
 * @param releasedAtUtc   最近一次解除时刻，epoch 毫秒，UTC；生效中或从未加入为 null
 * @param cancelledCount  最近一次加入名单时原子取消的未确认预占数量，单位笔
 * @param quotaReleased   最近一次加入名单时返还的两级额度次数（总/访客各计），单位次
 * @param cancellations   最近一次加入名单取消的预占明细；已确认曝光不在其中
 * @param quotaChanges    最近一次加入名单按公告/UTC 日聚合的额度变化
 */
public record SuppressionResponse(
        String visitorId,
        boolean active,
        Long suppressedAtUtc,
        Long releasedAtUtc,
        int cancelledCount,
        int quotaReleased,
        List<SuppressionCancellationItem> cancellations,
        List<SuppressionQuotaChange> quotaChanges
) {
}
