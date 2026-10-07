package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.RotationPool;
import com.example.starter.exposure.domain.RotationPoolMember;

import java.util.List;

/**
 * 缺额轮转池视图。
 *
 * @param poolId               轮转池编号
 * @param version              池配置版本
 * @param members              成员列表，按公告号字典序升序
 * @param pendingReservationId 待决选择的预占单编号；无待决选择时为 null
 * @param createdAtUtc         创建时刻，epoch 毫秒，UTC
 */
public record PoolResponse(
        String poolId,
        int version,
        List<PoolMemberView> members,
        String pendingReservationId,
        long createdAtUtc
) {

    /**
     * 池成员视图。
     *
     * @param campaignId       公告编号
     * @param weight           权重（1～10）
     * @param committedDeficit 已正式提交的缺额，单位次
     */
    public record PoolMemberView(
            String campaignId,
            int weight,
            long committedDeficit
    ) {
    }

    public static PoolResponse from(RotationPool pool, List<RotationPoolMember> members) {
        return new PoolResponse(
                pool.poolId(),
                pool.version(),
                members.stream()
                        .map(m -> new PoolMemberView(m.campaignId(), m.weight(), m.committedDeficit()))
                        .toList(),
                pool.pendingReservationId(),
                pool.createdAtUtc());
    }
}
