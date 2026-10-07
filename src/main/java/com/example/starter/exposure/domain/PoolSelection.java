package com.example.starter.exposure.domain;

/**
 * 池轮转选择记录 PO：一次选择对应一条曝光预占单，冻结选中公告身份，旧重放不重新选择。
 *
 * @param reservationId 关联的预占单编号
 * @param poolId        所属选择池编号
 * @param campaignId    选中的公告编号
 * @param visitorId     合成访客编号
 * @param createdAtUtc  选择时刻（epoch 毫秒，UTC）
 */
public record PoolSelection(
        String reservationId,
        String poolId,
        String campaignId,
        String visitorId,
        long createdAtUtc
) {
}
