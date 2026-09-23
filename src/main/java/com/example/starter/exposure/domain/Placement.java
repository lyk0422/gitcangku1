package com.example.starter.exposure.domain;

/**
 * 展示位 PO。每个公告最多 20 个唯一 code（含自动创建的 DEFAULT），创建后不可修改、不可删除。
 *
 * @param campaignId    所属公告编号
 * @param placementCode 展示位编号；DEFAULT 为兼容旧申请接口的默认展示位
 * @param dailyCap      该展示位每 UTC 日额度，单位次，取值 1～100000 且不超过公告日总额度
 * @param configVersion 该展示位创建时刻公告的配置版本；DEFAULT 为 1
 * @param createdAtUtc  创建时刻（epoch 毫秒，UTC）
 */
public record Placement(
        String campaignId,
        String placementCode,
        int dailyCap,
        int configVersion,
        long createdAtUtc
) {
}
