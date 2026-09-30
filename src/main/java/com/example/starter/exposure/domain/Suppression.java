package com.example.starter.exposure.domain;

/**
 * 访客抑制名单当前状态 PO。每个访客至多一行，由申请与加入名单流程确保行存在，
 * 以支持 SELECT ... FOR UPDATE 行锁串行化“加入名单”与“曝光预占/确认”竞争。
 *
 * @param visitorId        合成访客编号，全局唯一
 * @param active           是否处于抑制生效中：true=生效（新预占一律 409），false=未抑制或已解除
 * @param suppressedAtUtc  最近一次加入名单生效时刻，epoch 毫秒，UTC；从未加入过为 null
 * @param releasedAtUtc    最近一次解除时刻，epoch 毫秒，UTC；生效中或从未加入为 null
 * @param cancelledCount   最近一次加入名单时原子取消的未确认预占数量，单位笔
 * @param quotaReleased    最近一次加入名单时释放的两级额度次数（每笔预占释放总/访客各 1 次），单位次
 */
public record Suppression(
        String visitorId,
        boolean active,
        Long suppressedAtUtc,
        Long releasedAtUtc,
        int cancelledCount,
        int quotaReleased
) {
}
