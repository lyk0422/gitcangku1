package com.example.starter.api.dto;

/**
 * 核销历史只读视图。
 *
 * @param consumptionId 核销流水标识
 * @param reviewId      触发核销的审核标识
 * @param flightKey     航班业务标识
 * @param permitKey     使用的豁免包标识
 * @param regionKey     被核销区域标识
 * @param regionVersion 被核销区域版本
 * @param balanceBefore 扣减前剩余额度
 * @param balanceAfter  扣减后剩余额度
 * @param createdAt     核销时间，epoch 毫秒（UTC）
 */
public record ConsumptionDto(String consumptionId, String reviewId, String flightKey,
                             String permitKey, String regionKey, long regionVersion,
                             int balanceBefore, int balanceAfter, long createdAt) {
}
