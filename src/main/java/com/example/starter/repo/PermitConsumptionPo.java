package com.example.starter.repo;

/**
 * 豁免额度核销流水（不可变）。一次 CLEAR 航线审核对每个命中区域各产生一条，
 * 记录扣减前后余额；撤销豁免包与后续审核都不回写历史流水。
 *
 * @param consumptionId 核销流水唯一标识
 * @param reviewId      触发核销的航线审核标识
 * @param flightKey     审核业务航班标识
 * @param permitKey     使用的豁免包标识
 * @param regionKey     被核销的命中区域标识
 * @param regionVersion 核销时命中区域的版本
 * @param balanceBefore 扣减前剩余额度（次）
 * @param balanceAfter  扣减后剩余额度（次）
 * @param seq           同一次审核内的流水顺序，从 0 开始
 * @param createdAt     核销时间，epoch 毫秒（UTC）
 */
public record PermitConsumptionPo(String consumptionId, String reviewId, String flightKey,
                                  String permitKey, String regionKey, long regionVersion,
                                  int balanceBefore, int balanceAfter, int seq,
                                  long createdAt) {
}
