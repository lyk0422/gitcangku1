package com.example.starter.api.dto;

/**
 * 核销历史流水（只读查询）。
 *
 * @param redeemId      流水标识
 * @param permitKey     使用的豁免包标识
 * @param regionKey     被扣减额度的区域标识
 * @param flightKey     触发核销的飞行审核标识
 * @param balanceBefore 扣减前剩余额度
 * @param balanceAfter  扣减后剩余额度
 * @param reviewAt      审核指定 UTC 时刻，epoch 毫秒
 * @param createdAt     落库时间，epoch 毫秒（UTC）
 */
public record PermitRedeemDto(String redeemId, String permitKey, String regionKey,
                              String flightKey, int balanceBefore, int balanceAfter,
                              long reviewAt, long createdAt) {
}
