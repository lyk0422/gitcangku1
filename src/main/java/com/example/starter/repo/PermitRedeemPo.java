package com.example.starter.repo;

/**
 * 豁免额度核销流水（只追加，不可变）。
 *
 * @param redeemId      流水唯一标识
 * @param permitId      使用的豁免包标识
 * @param regionKey     被扣减额度的区域标识
 * @param flightKey     触发核销的飞行审核标识
 * @param balanceBefore 扣减前剩余额度
 * @param balanceAfter  扣减后剩余额度
 * @param reviewAt      审核指定时刻（epoch 毫秒，UTC）
 * @param createdAt     落库时间（epoch 毫秒，UTC）
 */
public record PermitRedeemPo(String redeemId, String permitId, String regionKey,
                             String flightKey, int balanceBefore, int balanceAfter,
                             long reviewAt, long createdAt) {
}
