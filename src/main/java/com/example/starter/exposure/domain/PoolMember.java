package com.example.starter.exposure.domain;

/**
 * 选择池成员 PO。缺额（{@link #committedDeficit}）为精确整数，可正可负；
 * 仅在关联预占确认时正式提交，取消/到期不推进，跨 UTC 日不清零。
 *
 * @param poolId           所属选择池编号
 * @param campaignId       成员公告编号
 * @param weight           轮转权重，正整数 1～10
 * @param committedDeficit 已正式提交的缺额计数，初始 0
 */
public record PoolMember(
        String poolId,
        String campaignId,
        int weight,
        long committedDeficit
) {
}
