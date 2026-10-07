package com.example.starter.exposure.web;

import java.util.List;

/**
 * 选择池视图。
 *
 * @param poolId              选择池编号
 * @param poolVersion         池版本号，当前恒为 1
 * @param members             池成员（按公告编号字典序），含已提交缺额
 * @param activeReservationId 当前 RESERVED 状态选择对应的预占单编号；无则为 null
 * @param createdAtUtc        创建时刻，epoch 毫秒，UTC
 */
public record PoolResponse(
        String poolId,
        int poolVersion,
        List<Member> members,
        String activeReservationId,
        long createdAtUtc
) {
    /**
     * 池成员视图。
     *
     * @param campaignId       成员公告编号
     * @param weight           轮转权重
     * @param committedDeficit 已正式提交的缺额计数，可正可负
     */
    public record Member(String campaignId, int weight, long committedDeficit) {
    }
}
