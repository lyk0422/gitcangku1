package com.example.starter.exposure.domain;

/**
 * 缺额轮转池成员 PO。每个成员关联一个已有公告，沿用该公告的两级 UTC 日额度。
 *
 * @param poolId           所属轮转池编号
 * @param campaignId       成员公告编号
 * @param weight           正整数权重，取值 1～10，创建后固定
 * @param committedDeficit 已正式提交的缺额（仅 CONFIRMED 时推进，取消/到期不推进），单位次
 */
public record RotationPoolMember(
        String poolId,
        String campaignId,
        int weight,
        long committedDeficit
) {
}
