package com.example.starter.consent.ledger.dto;

import com.example.starter.consent.ledger.QuotaStatus;

/**
 * 额度调整结果。
 *
 * @param subjectKey 主体标识
 * @param purpose    用途
 * @param epoch      额度归属授权代次
 * @param balance    调整后余额（单位：个）；状态为 CLEARED 时为 null
 * @param status     额度状态：ACTIVE / CLEARED
 */
public record QuotaAdjustResponse(String subjectKey, String purpose, int epoch, Long balance, QuotaStatus status) {
}
