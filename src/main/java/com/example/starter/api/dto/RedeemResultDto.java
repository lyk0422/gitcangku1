package com.example.starter.api.dto;

/**
 * 一次 CLEAR 核销中单个命中区域的额度扣减记录（审核快照冻结核销前后余额）。
 *
 * @param regionKey     被扣减额度的区域标识
 * @param balanceBefore 扣减前剩余额度
 * @param balanceAfter  扣减后剩余额度
 */
public record RedeemResultDto(String regionKey, int balanceBefore, int balanceAfter) {
}
