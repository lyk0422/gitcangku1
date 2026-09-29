package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 用途额度视图：返回某 epoch 某用途的额度、已用与剩余余额。
 *
 * @param subjectKey       主体标识
 * @param purpose          用途
 * @param epoch            授权代次
 * @param declared         该用途是否已在本 epoch 声明额度
 * @param quotaLimit       总额度（单位：对象数）；未声明时为 null
 * @param usedAmount       已核销对象数（账本累计扣减）；未声明时为 null
 * @param remainingBalance 剩余余额＝总额度－已用；未声明时为 null
 */
public record QuotaResponse(String subjectKey, Purpose purpose, int epoch, boolean declared,
                            Long quotaLimit, Long usedAmount, Long remainingBalance) {
}
